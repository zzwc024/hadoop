/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.hadoop.ozone.container.common.impl;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.hadoop.hdds.scm.TestUtils;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.server.datanode.StorageLocation;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.ozone.container.ContainerTestHelper;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainer;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;
import org.apache.hadoop.ozone.container.keyvalue.helpers.KeyUtils;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.utils.MetadataStore;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * The class for testing container deletion choosing policy.
 */
@Ignore
public class TestContainerDeletionChoosingPolicy {
  private static String path;
  private static ContainerSet containerSet;
  private static OzoneConfiguration conf;

  @Before
  public void init() throws Throwable {
    conf = new OzoneConfiguration();
    path = GenericTestUtils
        .getTempPath(TestContainerDeletionChoosingPolicy.class.getSimpleName());
    path += conf.getTrimmed(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT,
        OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT_DEFAULT);
    conf.set(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT, path);
  }

  @Test
  public void testRandomChoosingPolicy() throws IOException {
    File containerDir = new File(path);
    if (containerDir.exists()) {
      FileUtils.deleteDirectory(new File(path));
    }
    Assert.assertTrue(containerDir.mkdirs());

    conf.set(ScmConfigKeys.OZONE_SCM_CONTAINER_DELETION_CHOOSING_POLICY,
        RandomContainerDeletionChoosingPolicy.class.getName());
    List<StorageLocation> pathLists = new LinkedList<>();
    pathLists.add(StorageLocation.parse(containerDir.getAbsolutePath()));
    containerSet = new ContainerSet();

    int numContainers = 10;
    for (int i = 0; i < numContainers; i++) {
      KeyValueContainerData data = new KeyValueContainerData(new Long(i),
          ContainerTestHelper.CONTAINER_MAX_SIZE_GB);
      KeyValueContainer container = new KeyValueContainer(data, conf);
      containerSet.addContainer(container);
      Assert.assertTrue(
          containerSet.getContainerMap().containsKey(data.getContainerID()));
    }

    List<ContainerData> result0 = containerSet
        .chooseContainerForBlockDeletion(5);
    Assert.assertEquals(5, result0.size());

    // test random choosing
    List<ContainerData> result1 = containerSet
        .chooseContainerForBlockDeletion(numContainers);
    List<ContainerData> result2 = containerSet
        .chooseContainerForBlockDeletion(numContainers);

    boolean hasShuffled = false;
    for (int i = 0; i < numContainers; i++) {
      if (result1.get(i).getContainerID()
           != result2.get(i).getContainerID()) {
        hasShuffled = true;
        break;
      }
    }
    Assert.assertTrue("Chosen container results were same", hasShuffled);
  }

  @Test
  public void testTopNOrderedChoosingPolicy() throws IOException {
    File containerDir = new File(path);
    if (containerDir.exists()) {
      FileUtils.deleteDirectory(new File(path));
    }
    Assert.assertTrue(containerDir.mkdirs());

    conf.set(ScmConfigKeys.OZONE_SCM_CONTAINER_DELETION_CHOOSING_POLICY,
        TopNOrderedContainerDeletionChoosingPolicy.class.getName());
    List<StorageLocation> pathLists = new LinkedList<>();
    pathLists.add(StorageLocation.parse(containerDir.getAbsolutePath()));
    containerSet = new ContainerSet();
    DatanodeDetails datanodeDetails = TestUtils.getDatanodeDetails();

    int numContainers = 10;
    Random random = new Random();
    Map<Long, Integer> name2Count = new HashMap<>();
    // create [numContainers + 1] containers
    for (int i = 0; i <= numContainers; i++) {
      long containerId = RandomUtils.nextLong();
      KeyValueContainerData data = new KeyValueContainerData(new Long(i),
          ContainerTestHelper.CONTAINER_MAX_SIZE_GB);
      KeyValueContainer container = new KeyValueContainer(data, conf);
      containerSet.addContainer(container);
      Assert.assertTrue(
          containerSet.getContainerMap().containsKey(containerId));

      // don't create deletion blocks in the last container.
      if (i == numContainers) {
        break;
      }

      // create random number of deletion blocks and write to container db
      int deletionBlocks = random.nextInt(numContainers) + 1;
      // record <ContainerName, DeletionCount> value
      name2Count.put(containerId, deletionBlocks);
      for (int j = 0; j <= deletionBlocks; j++) {
        MetadataStore metadata = KeyUtils.getDB(data, conf);
        String blk = "blk" + i + "-" + j;
        byte[] blkBytes = DFSUtil.string2Bytes(blk);
        metadata.put(
            DFSUtil.string2Bytes(OzoneConsts.DELETING_KEY_PREFIX + blk),
            blkBytes);
      }
    }

    List<ContainerData> result0 = containerSet
        .chooseContainerForBlockDeletion(5);
    Assert.assertEquals(5, result0.size());

    List<ContainerData> result1 = containerSet
        .chooseContainerForBlockDeletion(numContainers + 1);
    // the empty deletion blocks container should not be chosen
    Assert.assertEquals(numContainers, result1.size());

    // verify the order of return list
    int lastCount = Integer.MAX_VALUE;
    for (ContainerData data : result1) {
      int currentCount = name2Count.remove(data.getContainerID());
      // previous count should not smaller than next one
      Assert.assertTrue(currentCount > 0 && currentCount <= lastCount);
      lastCount = currentCount;
    }
    // ensure all the container data are compared
    Assert.assertEquals(0, name2Count.size());
  }
}
