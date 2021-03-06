/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.TestFileCreation;
import org.apache.hadoop.hdfs.TestFileHardLink;
import org.apache.hadoop.hdfs.server.namenode.AvatarNode;
import org.apache.hadoop.hdfs.server.namenode.FinalizeCheckpointException;
import org.apache.hadoop.hdfs.server.namenode.FSImageTestUtil.CheckpointTrigger;
import org.apache.hadoop.hdfs.util.InjectionEvent;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.InjectionEventI;
import org.apache.hadoop.util.InjectionHandler;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.*;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestAvatarCheckpointing {
  
  final static Log LOG = LogFactory.getLog(TestAvatarCheckpointing.class);
  
  protected static MiniAvatarCluster cluster;
  protected static Configuration conf;
  protected static FileSystem fs;
  protected static Random random = new Random();

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    MiniAvatarCluster.createAndStartZooKeeper();
  }
  
  private static void setUp(String name) throws Exception {
    setUp(3600, name, true);
  }
  
  private static void setUp(long ckptPeriod, String name, boolean waitForCheckpoint)
      throws Exception {
    LOG.info("------------------- test: " + name + " START ----------------");
    conf = new Configuration();
    
    conf.setBoolean("fs.ha.retrywrites", true);
    conf.setBoolean("fs.checkpoint.enabled", true);
    conf.setLong("fs.checkpoint.period", ckptPeriod);
    conf.setBoolean("fs.checkpoint.wait", waitForCheckpoint);
    
    cluster = new MiniAvatarCluster(conf, 2, true, null, null);
    fs = cluster.getFileSystem();
  }

  @After
  public void tearDown() throws Exception {
    tearDownCluster();
  }
  
  protected static void tearDownCluster() throws Exception {
    fs.close();
    cluster.shutDown();
    InjectionHandler.clear();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    MiniAvatarCluster.shutDownZooKeeper();
  }

  public static void createEdits(int nEdits) throws IOException {
    for (int i = 0; i < nEdits / 2; i++) {
      // Create file ends up logging two edits to the edit log, one for create
      // file and one for bumping up the generation stamp
      fs.create(new Path("/" + random.nextInt()));
    }
  }
  
  public static long getCurrentTxId(AvatarNode avatar) {
    return avatar.getFSImage().getEditLog().getCurrentTxId();
  }
  
  //////////////////////////////
  
  @Test
  public void testHardLinkWithCheckPoint() throws Exception {
    TestAvatarCheckpointingHandler h = new TestAvatarCheckpointingHandler(null, null, false);
    InjectionHandler.set(h);
    setUp("testHardLinkWithCheckPoint");
    
    // Create a new file
    Path root = new Path("/user/");
    Path file10 = new Path(root, "file1");
    FSDataOutputStream stm1 = TestFileCreation.createFile(fs, file10, 1);
    byte[] content = TestFileCreation.writeFile(stm1);
    stm1.close();

    LOG.info("Create the hardlinks");
    Path file11 =  new Path(root, "file-11");
    Path file12 =  new Path(root, "file-12");
    fs.hardLink(file10, file11);
    fs.hardLink(file11, file12);

    LOG.info("Verify the hardlinks");
    TestFileHardLink.verifyLinkedFileIdenticial(fs, cluster.getPrimaryAvatar(0).avatar,
        fs.getFileStatus(file10), fs.getFileStatus(file11), content);
    TestFileHardLink.verifyLinkedFileIdenticial(fs, cluster.getPrimaryAvatar(0).avatar,
        fs.getFileStatus(file10), fs.getFileStatus(file12), content);

    LOG.info("NN checkpointing");
    h.doCheckpoint();
    
    // Restart the namenode
    LOG.info("NN restarting");
    cluster.restartAvatarNodes();
    
    // Verify the hardlinks again
    LOG.info("Verify the hardlinks again after the NN restarts");
    TestFileHardLink.verifyLinkedFileIdenticial(fs, cluster.getPrimaryAvatar(0).avatar, 
        fs.getFileStatus(file10), fs.getFileStatus(file11), content);
    TestFileHardLink.verifyLinkedFileIdenticial(fs, cluster.getPrimaryAvatar(0).avatar, 
        fs.getFileStatus(file10), fs.getFileStatus(file12), content);
  }
  
  @Test
  public void testFailSuccFailQuiesce() throws Exception {
    LOG.info("TEST: ----> testFailCheckpointOnceAndSucceed");
    // fail once
    TestAvatarCheckpointingHandler h = new TestAvatarCheckpointingHandler(null,
        null, true);
    InjectionHandler.set(h);
    setUp("testFailSuccFailQuiesce");
    createEdits(20);
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
    
    
    try {
      h.failNextCheckpoint = true;
      h.doCheckpoint();
      fail("Should get IOException here");
    } catch (Exception e) { 
      // checkpoint fails during finalizations (see the checkpointing handler)
      assertTrue(e instanceof FinalizeCheckpointException);
      assertTrue(AvatarSetupUtil.isIngestAlive(standby));
      LOG.warn("Expected: Checkpoint failed", e);
    }
    
    // current txid should be 20 + SLS + ENS + SLS + initial
    assertEquals(25, getCurrentTxId(primary));
    
    h.failNextCheckpoint = false;
    
    // checkpoint should succeed
    h.doCheckpoint();
    
    // another roll adds 2 transactions
    assertEquals(27, getCurrentTxId(primary));
    
    h.failNextCheckpoint = true;    
    try {
      h.doCheckpoint();
      fail("Should get IOException here");
    } catch (Exception e) { 
      LOG.warn("Expected: Checkpoint failed", e);
    }
    
    // another roll adds 2 transactions
    assertEquals(29, getCurrentTxId(primary));
    
    createEdits(20);
    standby.quiesceStandby(getCurrentTxId(primary)-1);
    assertEquals(49, getCurrentTxId(primary));
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby));
  }
  
  @Test
  public void testFailCheckpointMultiAndCrash() throws Exception {
    LOG.info("TEST: ----> testFailCheckpointMultiAndCrash");
    TestAvatarCheckpointingHandler h = new TestAvatarCheckpointingHandler(null,
        null, true);
    InjectionHandler.set(h);
    setUp("testFailCheckpointMultiAndCrash");
    createEdits(20);
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
    
    try {
      h.failNextCheckpoint = true;
      h.doCheckpoint();
      fail("Should get IOException here");
    } catch (IOException e) { 
      // checkpoint fails during finalization (see the checkpointing handler)
      assertTrue(e instanceof FinalizeCheckpointException);
      assertTrue(AvatarSetupUtil.isIngestAlive(standby));
      LOG.info("Expected: Checkpoint failed", e);
    }
    
    // current txid should be 20 + SLS + ENS + SLS + initial ckpt
    assertEquals(25, getCurrentTxId(primary));
    
    try {
      h.doCheckpoint();
      fail("Should get IOException here");
    } catch (IOException e) { 
      // checkpoint fails during finalization (see the checkpointing handler)
      assertTrue(e instanceof FinalizeCheckpointException);
      assertTrue(AvatarSetupUtil.isIngestAlive(standby));
      LOG.info("Expected: Checkpoint failed", e);
    }
    
    // roll adds 2 transactions
    assertEquals(27, getCurrentTxId(primary));
    
    try {
      h.doCheckpoint();
      fail("Should get IOException here");
    } catch (Exception e) {
      // checkpoint fails during finalization (see the checkpointing handler)
      assertTrue(e instanceof FinalizeCheckpointException);
      assertTrue(AvatarSetupUtil.isIngestAlive(standby));
      LOG.info("Expected: Checkpoint failed", e);
    }
    
    // roll adds 2 transactions
    assertEquals(29, getCurrentTxId(primary));
  }
  
  @Test
  public void testFailCheckpointOnceAndRestartStandby() throws Exception {
    LOG.info("TEST: ----> testFailCheckpointOnceAndRestartStandby");
    TestAvatarCheckpointingHandler h = new TestAvatarCheckpointingHandler(null,
        null, true);
    InjectionHandler.set(h);
    setUp("testFailCheckpointOnceAndRestartStandby");
    createEdits(20);
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;

    // Wait for first checkpoint.
    while (standby.getStandby().getLastCheckpointTime() == 0) {
      LOG.info("Waiting for standby to do checkpoint");
      Thread.sleep(1000);
    }
    
    try {
      h.failNextCheckpoint = true;
      h.doCheckpoint();
      fail("Should get IOException here");
    } catch (IOException e) { 
      // checkpoint fails during finalization (see the checkpointing handler)
      assertTrue(e instanceof FinalizeCheckpointException);
      assertTrue(AvatarSetupUtil.isIngestAlive(standby));
      LOG.info("Expected: Checkpoint failed", e);
    }
    
    // current txid should be 20 + SLS + ENS + SLS + initial ckpt
    assertEquals(25, getCurrentTxId(primary));
    
    cluster.killStandby(0);
    h.failNextCheckpoint = false;
    
    cluster.restartStandby(0); // ads one checkpoint
    standby = cluster.getStandbyAvatar(0).avatar;
    while (standby.getStandby().getLastCheckpointTime() == 0) {
      LOG.info("Waiting for standby to do checkpoint");
      Thread.sleep(1000);
    }
    
    // checkpoint should succeed
    h.doCheckpoint();
    
    // roll adds two transactions
    assertEquals(29, getCurrentTxId(primary));
    
    createEdits(20);
    standby.quiesceStandby(getCurrentTxId(primary)-1);
    assertEquals(49, getCurrentTxId(primary));
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby));
  }
  
  @Test
  public void testFailCheckpointOnCorruptImage() throws Exception {
    LOG.info("TEST: ----> testFailCheckpointOnCorruptImage");
    TestAvatarCheckpointingHandler h = new TestAvatarCheckpointingHandler(
        null, null, false);
    InjectionHandler.set(h);
    
    // first checkpoint will succeed (most recent ckptxid = 1)
    setUp(3600, "testFailCheckpointOnCorruptImage", true);   
    // image will be corrupted for second - manual checkpoint
    h.corruptImage = true;
    
    createEdits(20);
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
   
    // do second checkpoint
    try {
      h.doCheckpoint();
      fail("Should get IOException here");
    } catch (IOException e) {  
      // checkpoint fails during finalizations (see the checkpointing handler)
      assertTrue(e instanceof FinalizeCheckpointException);
      assertTrue(AvatarSetupUtil.isIngestAlive(standby));
    }
    assertEquals(1, primary.getCheckpointSignature().getMostRecentCheckpointTxId());
  }
  
  @Test
  public void testCheckpointReprocessEdits() throws Exception {
    LOG.info("TEST: ----> testCheckpointReprocessEdits");
    TestAvatarCheckpointingHandler h = new TestAvatarCheckpointingHandler(null,
        null, false);   
    setUp("testCheckpointReprocessEdits");
    createEdits(20);
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;

    h.reprocessIngest = true;
    // set the handler later no to interfere with the previous checkpoint
    InjectionHandler.set(h);
    // checkpoint should be ok
    h.doCheckpoint();
    assertEquals(23, primary.getCheckpointSignature()
        .getMostRecentCheckpointTxId());
  }
  
  protected static TestAvatarCheckpointingHandler testQuiesceInterruption(
      InjectionEvent stopOnEvent, boolean testCancellation, boolean rollAfterQuiesce)
      throws Exception {
    return testQuiesceInterruption(stopOnEvent,
        InjectionEvent.STANDBY_QUIESCE_INITIATED, false, testCancellation,
        rollAfterQuiesce);
  }
 
  protected static TestAvatarCheckpointingHandler testQuiesceInterruption(
      InjectionEvent stopOnEvent, InjectionEvent waitUntilEvent, boolean scf,
      boolean testCancellation, boolean rollAfterQuiesce) throws Exception {
    LOG.info("TEST Quiesce during checkpoint : " + stopOnEvent
        + " waiting on: " + waitUntilEvent);
    TestAvatarCheckpointingHandler h = new TestAvatarCheckpointingHandler(
        stopOnEvent, waitUntilEvent, scf);
    InjectionHandler.set(h);
    setUp(3, "testQuiesceInterruption", false); //simulate interruption, no ckpt failure   
    
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
    createEdits(40);

    while (!h.receivedEvents.contains(stopOnEvent)) {
      LOG.info("Waiting for event : " + stopOnEvent);
      Thread.sleep(1000);
    }
    standby.quiesceStandby(getCurrentTxId(primary)-1);
    // edits + SLS + ELS + SLS (checkpoint fails, but roll happened)
    assertEquals(43, getCurrentTxId(primary));

    // if quiesce happened before roll, the standby will be behind by 1 transaction
    // which will be reclaimed by opening the log after
    long extraTransaction = rollAfterQuiesce ? 1 : 0;
    
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby) + extraTransaction);
    // make sure the checkpoint indeed failed
    assertTrue(h.receivedEvents
        .contains(InjectionEvent.STANDBY_EXIT_CHECKPOINT_EXCEPTION));
    if (testCancellation) {
      assertTrue(h.receivedEvents
          .contains(InjectionEvent.SAVE_NAMESPACE_CONTEXT_EXCEPTION));
    }
    return h;
  }

  static class TestAvatarCheckpointingHandler extends InjectionHandler {
    // specifies where the thread should wait for interruption
    
    public Set<InjectionEventI> receivedEvents = Collections
        .synchronizedSet(new HashSet<InjectionEventI>());
    private InjectionEvent stopOnEvent;
    private InjectionEvent waitUntilEvent;

    private boolean simulateCheckpointFailure = false;
    private boolean failNextCheckpoint = false;

    public boolean corruptImage = false;
    public boolean reprocessIngest = false;
    
    // for simulateing that edits.new does not exist
    // and that the ingests gets recreated after upload
    public boolean simulateEditsNotExists = false;
    public boolean simulateEditsNotExistsDone = false;
    public boolean ingestRecreatedAfterFailure = false;
    
    private CheckpointTrigger ckptTrigger = new CheckpointTrigger();

    public TestAvatarCheckpointingHandler(InjectionEvent stopOnEvent,
        InjectionEvent waitUntilEvent, boolean scf) {
      this.stopOnEvent = stopOnEvent;
      this.waitUntilEvent = waitUntilEvent;
      simulateCheckpointFailure = scf;
    }
    
    @Override
    protected boolean _falseCondition(InjectionEventI event, Object... args) {
      return ckptTrigger.triggerCheckpoint(event);
    }
    
    @Override
    protected void _processEvent(InjectionEventI event, Object... args) {
      LOG.debug("processEvent: processing event: " + event);    
      receivedEvents.add(event);
      if (stopOnEvent == event) {
        LOG.info("WAITING ON: " + event);
        while (!receivedEvents.contains(waitUntilEvent)) {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
        LOG.info("WAITING ON------------------- received : " + waitUntilEvent);
      }
      if (simulateEditsNotExistsDone && 
          event == InjectionEvent.STANDBY_CREATE_INGEST_RUNLOOP) {
        ingestRecreatedAfterFailure = true;
      }
      if (event == InjectionEvent.STANDBY_BEFORE_PUT_IMAGE && corruptImage) {
        File imageFile = (File)args[0];
        LOG.info("Corrupting image file " + imageFile);
        FileOutputStream out = null;
        try {
          out = new FileOutputStream(imageFile);
          out.getChannel().truncate(4);
        } catch (IOException ioe) {
          LOG.error("Exception when truncating image", ioe);
          // ignore, the test will fail 
          // if the checkpoint succeeds
        } finally {
          IOUtils.closeStream(out);
        }
      }
      ckptTrigger.checkpointDone(event, args);
    }

    @Override
    protected void _processEventIO(InjectionEventI event, Object... args)
        throws IOException {
      LOG.debug("processEventIO: processing event: " + event);

      if(simulateCheckpointFailure) {
        if (event == InjectionEvent.STANDBY_BEFORE_ROLL_IMAGE
            && failNextCheckpoint) {
          throw new IOException("Simultaing checkpoint failure");
        }
      } 
      if (event == InjectionEvent.INGEST_CLEAR_STANDBY_STATE && reprocessIngest) {
        reprocessIngest = false;
        throw new IOException("Simulating ingest ending crash");
      }
      _processEvent(event, args);
    } 
     
    void doCheckpoint() throws Exception { 
      ckptTrigger.doCheckpoint();  
    }  
  }
}
