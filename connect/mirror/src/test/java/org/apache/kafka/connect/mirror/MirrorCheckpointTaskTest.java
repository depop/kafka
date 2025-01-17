/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.mirror;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.connect.source.SourceRecord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MirrorCheckpointTaskTest {

    @Test
    public void testDownstreamTopicRenaming() {
        MirrorCheckpointTask mirrorCheckpointTask = new MirrorCheckpointTask("source1", "target2",
            new DefaultReplicationPolicy(), null, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        assertEquals(new TopicPartition("source1.topic3", 4),
            mirrorCheckpointTask.renameTopicPartition(new TopicPartition("topic3", 4)),
                "Renaming source1.topic3 failed");
        assertEquals(new TopicPartition("topic3", 5),
            mirrorCheckpointTask.renameTopicPartition(new TopicPartition("target2.topic3", 5)),
                "Renaming target2.topic3 failed");
        assertEquals(new TopicPartition("source1.source6.topic7", 8),
            mirrorCheckpointTask.renameTopicPartition(new TopicPartition("source6.topic7", 8)),
                "Renaming source1.source6.topic7 failed");
    }

    @Test
    public void testCheckpoint() {
        OffsetSyncStoreTest.FakeOffsetSyncStore offsetSyncStore = new OffsetSyncStoreTest.FakeOffsetSyncStore();
        MirrorCheckpointTask mirrorCheckpointTask = new MirrorCheckpointTask("source1", "target2",
            new DefaultReplicationPolicy(), offsetSyncStore, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        offsetSyncStore.sync(new TopicPartition("topic1", 2), 3L, 4L);
        offsetSyncStore.sync(new TopicPartition("target2.topic5", 6), 7L, 8L);
        Checkpoint checkpoint1 = mirrorCheckpointTask.checkpoint("group9", new TopicPartition("topic1", 2),
            new OffsetAndMetadata(10, null));
        SourceRecord sourceRecord1 = mirrorCheckpointTask.checkpointRecord(checkpoint1, 123L);
        assertEquals(new TopicPartition("source1.topic1", 2), checkpoint1.topicPartition(),
                "checkpoint group9 source1.topic1 failed");
        assertEquals("group9", checkpoint1.consumerGroupId(),
                "checkpoint group9 consumerGroupId failed");
        assertEquals("group9", Checkpoint.unwrapGroup(sourceRecord1.sourcePartition()),
                "checkpoint group9 sourcePartition failed");
        assertEquals(10, checkpoint1.upstreamOffset(),
                "checkpoint group9 upstreamOffset failed");
        assertEquals(11, checkpoint1.downstreamOffset(),
                "checkpoint group9 downstreamOffset failed");
        assertEquals(123L, sourceRecord1.timestamp().longValue(),
                "checkpoint group9 timestamp failed");
        Checkpoint checkpoint2 = mirrorCheckpointTask.checkpoint("group11", new TopicPartition("target2.topic5", 6),
            new OffsetAndMetadata(12, null));
        SourceRecord sourceRecord2 = mirrorCheckpointTask.checkpointRecord(checkpoint2, 234L);
        assertEquals(new TopicPartition("topic5", 6), checkpoint2.topicPartition(),
                "checkpoint group11 topic5 failed");
        assertEquals("group11", checkpoint2.consumerGroupId(),
                "checkpoint group11 consumerGroupId failed");
        assertEquals("group11", Checkpoint.unwrapGroup(sourceRecord2.sourcePartition()),
                "checkpoint group11 sourcePartition failed");
        assertEquals(12, checkpoint2.upstreamOffset(),
                "checkpoint group11 upstreamOffset failed");
        assertEquals(13, checkpoint2.downstreamOffset(),
                "checkpoint group11 downstreamOffset failed");
        assertEquals(234L, sourceRecord2.timestamp().longValue(),
                    "checkpoint group11 timestamp failed");
    }

    @Test
    public void testSyncOffset() {
        Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset = new HashMap<>();
        Map<TopicPartition, Long> topicPartitionEndOffset = new HashMap<>();
        Map<String, List<Checkpoint>> checkpointsPerConsumerGroup = new HashMap<>();

        testSyncOffsetCommon(idleConsumerGroupsOffset, checkpointsPerConsumerGroup, topicPartitionEndOffset);

        MirrorCheckpointTask mirrorCheckpointTask = new MirrorCheckpointTask("source1", "target2",
            new DefaultReplicationPolicy(), null, idleConsumerGroupsOffset, checkpointsPerConsumerGroup, topicPartitionEndOffset);

        Map<String, Map<TopicPartition, OffsetAndMetadata>> output = mirrorCheckpointTask.syncGroupOffset();
        TopicPartition t1p0 = new TopicPartition("topic1", 0);
        TopicPartition t2p0 = new TopicPartition("topic2", 0);
        assertEquals(101, output.get("consumer1").get(t1p0).offset());
        assertEquals(51, output.get("consumer2").get(t2p0).offset());
    }
    
    @Test
    public void testSyncOffsetTopicOutOfRetentionUpstream() {
        // test scenario:
        // 1. Create a topic with 1 partition on the source cluster, with short `retention.ms`
        // 2. Create a consumer that consumes from the source topic
        // 3. Send X messages to the source topic. These should get consumed by the source consumer
        // 4. Offset for this consumer on source cluster should be X
        // 5. Wait until the retention policy deletes the records
        // 6. Start MM2 with `source->target.sync.group.offsets.enabled = true`
        // 7. Observe on the target cluster that consumer offset should be 0, log-end-offset is 0, and lag is 0.
        Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset = new HashMap<>();
        Map<TopicPartition, Long> topicPartitionEndOffset = new HashMap<>();
        Map<String, List<Checkpoint>> checkpointsPerConsumerGroup = new HashMap<>();

        testSyncOffsetCommon(idleConsumerGroupsOffset, checkpointsPerConsumerGroup, topicPartitionEndOffset);
        
        TopicPartition t1p0 = new TopicPartition("topic1", 0);
        TopicPartition t2p0 = new TopicPartition("topic2", 0);
        // assume when mirrormaker starts, the messages from upstream topic have been deleted due to retention policy
        // thus mirrormaker does not mirror any message, end offsets are 0
        topicPartitionEndOffset.put(t1p0, 0L);
        topicPartitionEndOffset.put(t2p0, 0L);
        
        // also downstream consumer does not have consumer info
        idleConsumerGroupsOffset.clear();

        MirrorCheckpointTask mirrorCheckpointTask = new MirrorCheckpointTask("source1", "target2",
            new DefaultReplicationPolicy(), null, idleConsumerGroupsOffset, checkpointsPerConsumerGroup, topicPartitionEndOffset);

        Map<String, Map<TopicPartition, OffsetAndMetadata>> output = mirrorCheckpointTask.syncGroupOffset();
        // due to no message is mirrored by mirrormaker and downstream consumer does not exist yet, sync offset to 0 to let 
        // downstream consumer (auto.offset.reset) decide where to start consume in this case
        assertEquals(0, output.get("consumer1").get(t1p0).offset());
        assertEquals(0, output.get("consumer2").get(t2p0).offset());
    }
    
    @Test
    public void testSyncOffsetNewPartitionUpstream() {
        // test scenario:
        // 1. Alter the source topic by increasing number of partitions by 1
        // 2. Create a consumer that consumes from the source topic
        // 3. Send some messages to the source topic. Assume Y messages are consumed by the source consumer from new partition
        // 4. Offset for this consumer on the new source partition should be Y
        // 5. Wait until the retention policy deletes the records of source topic
        // 6. Start MM2 with `source->target.sync.group.offsets.enabled = true`
        // 7. Observe on the target cluster that consumer offset of the new partition should be 0, log-end-offset is 0, and lag is 0.
        Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset = new HashMap<>();
        Map<TopicPartition, Long> topicPartitionEndOffset = new HashMap<>();
        Map<String, List<Checkpoint>> checkpointsPerConsumerGroup = new HashMap<>();

        testSyncOffsetCommon(idleConsumerGroupsOffset, checkpointsPerConsumerGroup, topicPartitionEndOffset);
        
        // new partition from upstream cluster
        TopicPartition t2p1 = new TopicPartition("topic2", 1);
        // new partition from downstream cluster, assume just 60 messages are mirrored
        topicPartitionEndOffset.put(t2p1, 60L);

        // 'cpC2T2p1' denotes 'checkpoint' of topic2, partition1 for consumer 2
        // assume 61 messages have been consumed from upstream cluster
        Checkpoint cpC2T2P1 = new Checkpoint("consumer2", t2p1, 60, 61, "metadata");
        checkpointsPerConsumerGroup.get("consumer2").add(cpC2T2P1);

        MirrorCheckpointTask mirrorCheckpointTask = new MirrorCheckpointTask("source1", "target2",
            new DefaultReplicationPolicy(), null, idleConsumerGroupsOffset, checkpointsPerConsumerGroup, topicPartitionEndOffset);

        Map<String, Map<TopicPartition, OffsetAndMetadata>> output = mirrorCheckpointTask.syncGroupOffset();
        TopicPartition t1p0 = new TopicPartition("topic1", 0);
        TopicPartition t2p0 = new TopicPartition("topic2", 0);
        assertEquals(101, output.get("consumer1").get(t1p0).offset());
        assertEquals(51, output.get("consumer2").get(t2p0).offset());
        // due to converted offset (61) is larger than end offset of downstream topic, sync offset to 0 to let 
        // downstream consumer (auto.offset.reset) decide where to start consume in this case
        assertEquals(0, output.get("consumer2").get(t2p1).offset());
    }
    
    private void testSyncOffsetCommon(Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset, 
            Map<String, List<Checkpoint>> checkpointsPerConsumerGroup, Map<TopicPartition, Long> topicPartitionEndOffset) {
        String consumer1 = "consumer1";
        String consumer2 = "consumer2";

        String topic1 = "topic1";
        String topic2 = "topic2";

        // 'c1t1' denotes consumer offsets of all partitions of topic1 for consumer1
        Map<TopicPartition, OffsetAndMetadata> c1t1 = new HashMap<>();
        // 't1p0' denotes topic1, partition 0
        TopicPartition t1p0 = new TopicPartition(topic1, 0);

        c1t1.put(t1p0, new OffsetAndMetadata(100));

        Map<TopicPartition, OffsetAndMetadata> c2t2 = new HashMap<>();
        TopicPartition t2p0 = new TopicPartition(topic2, 0);

        c2t2.put(t2p0, new OffsetAndMetadata(50));
        
        // 'cpC1T1P0' denotes 'checkpoint' of topic1, partition 0 for consumer1
        Checkpoint cpC1T1P0 = new Checkpoint(consumer1, t1p0, 200, 101, "metadata");

        // 'cpC2T2p0' denotes 'checkpoint' of topic2, partition 0 for consumer2
        Checkpoint cpC2T2P0 = new Checkpoint(consumer2, t2p0, 100, 51, "metadata");
        
        // 'checkpointListC1' denotes 'checkpoint' list for consumer1
        List<Checkpoint> checkpointListC1 = new ArrayList<>();
        checkpointListC1.add(cpC1T1P0);

        // 'checkpointListC2' denotes 'checkpoint' list for consumer2
        List<Checkpoint> checkpointListC2 = new ArrayList<>();
        checkpointListC2.add(cpC2T2P0);

        checkpointsPerConsumerGroup.put(consumer1, checkpointListC1);
        checkpointsPerConsumerGroup.put(consumer2, checkpointListC2);
        
        idleConsumerGroupsOffset.put(consumer1, c1t1);
        idleConsumerGroupsOffset.put(consumer2, c2t2);
        
        topicPartitionEndOffset.put(t1p0, 101L);
        topicPartitionEndOffset.put(t2p0, 51L);
    }
}
