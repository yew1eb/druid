/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.server.coordinator.helper;

import com.google.inject.Inject;
import io.druid.client.indexing.IndexingServiceClient;
import io.druid.java.util.common.ISE;
import io.druid.java.util.common.logger.Logger;
import io.druid.server.coordinator.CoordinatorCompactionConfig;
import io.druid.server.coordinator.CoordinatorDynamicConfig;
import io.druid.server.coordinator.CoordinatorStats;
import io.druid.server.coordinator.DruidCoordinatorRuntimeParams;
import io.druid.timeline.DataSegment;
import io.druid.timeline.VersionedIntervalTimeline;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DruidCoordinatorSegmentCompactor implements DruidCoordinatorHelper
{
  static final String COMPACT_TASK_COUNT = "compactTaskCount";
  static final String SEGMENTS_WAIT_COMPACT = "segmentsWaitCompact";

  // Should be synced with CompactionTask.TYPE
  private static final String COMPACT_TASK_TYPE = "compact";
  private static final Logger LOG = new Logger(DruidCoordinatorSegmentCompactor.class);

  private final CompactionSegmentSearchPolicy policy = new NewestSegmentFirstPolicy();
  private final IndexingServiceClient indexingServiceClient;

  @Inject
  public DruidCoordinatorSegmentCompactor(IndexingServiceClient indexingServiceClient)
  {
    this.indexingServiceClient = indexingServiceClient;
  }

  @Override
  public DruidCoordinatorRuntimeParams run(DruidCoordinatorRuntimeParams params)
  {
    LOG.info("Run coordinator segment compactor");

    final CoordinatorDynamicConfig dynamicConfig = params.getCoordinatorDynamicConfig();
    final CoordinatorStats stats = new CoordinatorStats();

    if (dynamicConfig.getMaxCompactionTaskSlots() > 0) {
      Map<String, VersionedIntervalTimeline<String, DataSegment>> dataSources = params.getDataSources();
      List<CoordinatorCompactionConfig> compactionConfigList = dynamicConfig.getCompactionConfigs();

      if (compactionConfigList != null && !compactionConfigList.isEmpty()) {
        Map<String, CoordinatorCompactionConfig> compactionConfigs = compactionConfigList
            .stream()
            .collect(Collectors.toMap(CoordinatorCompactionConfig::getDataSource, Function.identity()));
        final int numRunningCompactTasks = indexingServiceClient
            .getRunningTasks()
            .stream()
            .filter(status -> status.getType().equals(COMPACT_TASK_TYPE))
            .collect(Collectors.toList())
            .size();
        final CompactionSegmentIterator iterator = policy.reset(compactionConfigs, dataSources);

        final int compactionTaskCapacity = (int) Math.min(
            indexingServiceClient.getTotalWorkerCapacity() * dynamicConfig.getCompactionTaskSlotRatio(),
            dynamicConfig.getMaxCompactionTaskSlots()
        );
        final int numAvailableCompactionTaskSlots = numRunningCompactTasks > 0 ?
                                                    compactionTaskCapacity - numRunningCompactTasks :
                                                    // compactionTaskCapacity might be 0 if totalWorkerCapacity is low.
                                                    // This guarantees that at least one slot is available if
                                                    // compaction is enabled and numRunningCompactTasks is 0.
                                                    Math.max(1, compactionTaskCapacity);
        LOG.info("Running tasks [%d/%d]", numRunningCompactTasks, compactionTaskCapacity);
        if (numAvailableCompactionTaskSlots > 0) {
          stats.accumulate(doRun(compactionConfigs, numAvailableCompactionTaskSlots, iterator));
        } else {
          stats.accumulate(makeStats(0, iterator));
        }
      } else {
        LOG.info("compactionConfig is empty. Skip.");
      }
    } else {
      LOG.info("maxCompactionTaskSlots was set to 0. Skip compaction");
    }

    return params.buildFromExisting()
                 .withCoordinatorStats(stats)
                 .build();
  }

  private CoordinatorStats doRun(
      Map<String, CoordinatorCompactionConfig> compactionConfigs,
      int numAvailableCompactionTaskSlots,
      CompactionSegmentIterator iterator
  )
  {
    int numSubmittedTasks = 0;

    for (; iterator.hasNext() && numSubmittedTasks < numAvailableCompactionTaskSlots; numSubmittedTasks++) {
      final List<DataSegment> segmentsToCompact = iterator.next();

      final String dataSourceName = segmentsToCompact.get(0).getDataSource();

      if (segmentsToCompact.size() > 1) {
        final CoordinatorCompactionConfig config = compactionConfigs.get(dataSourceName);
        final String taskId = indexingServiceClient.compactSegments(
            segmentsToCompact,
            config.getTaskPriority(),
            config.getTuningConfig(),
            config.getTaskContext()
        );
        LOG.info("Submitted a compactTask[%s] for segments[%s]", taskId, segmentsToCompact);
      } else if (segmentsToCompact.size() == 1) {
        throw new ISE("Found one segments[%s] to compact", segmentsToCompact);
      } else {
        throw new ISE("Failed to find segments for dataSource[%s]", dataSourceName);
      }
    }

    return makeStats(numSubmittedTasks, iterator);
  }

  private CoordinatorStats makeStats(int numCompactionTasks, CompactionSegmentIterator iterator)
  {
    final CoordinatorStats stats = new CoordinatorStats();
    stats.addToGlobalStat(COMPACT_TASK_COUNT, numCompactionTasks);
    iterator.remainingSegments().object2LongEntrySet().fastForEach(
        entry -> {
          final String dataSource = entry.getKey();
          final long numSegmentsWaitCompact = entry.getLongValue();
          stats.addToDataSourceStat(SEGMENTS_WAIT_COMPACT, dataSource, numSegmentsWaitCompact);
        }
    );
    return stats;
  }
}
