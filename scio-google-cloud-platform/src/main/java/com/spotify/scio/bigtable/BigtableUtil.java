/*
 * Copyright 2016 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.bigtable;

import com.google.bigtable.admin.v2.Cluster;
import com.google.bigtable.admin.v2.ListClustersRequest;
import com.google.bigtable.admin.v2.ListClustersResponse;
import com.google.cloud.bigtable.config.BigtableOptions;
import com.google.cloud.bigtable.grpc.BigtableClusterUtilities;
import com.google.cloud.bigtable.grpc.BigtableInstanceClient;
import com.google.cloud.bigtable.grpc.BigtableInstanceGrpcClient;
import com.google.cloud.bigtable.grpc.io.ChannelPool;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.joda.time.Duration;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utilities to deal with Bigtable. */
public final class BigtableUtil {

  private BigtableUtil() {}

  private static final Logger LOG = LoggerFactory.getLogger(BigtableUtil.class);

  private static final PeriodFormatter formatter =
      new PeriodFormatterBuilder()
          .appendDays()
          .appendSuffix("d")
          .appendHours()
          .appendSuffix("h")
          .appendMinutes()
          .appendSuffix("m")
          .appendSeconds()
          .appendSuffix("s")
          .toFormatter();

  /**
   * Updates all clusters within the specified Bigtable instance to a specified number of nodes.
   * Useful for increasing the number of nodes at the beginning of a job and decreasing it at the
   * end to lower costs yet still get high throughput during bulk ingests/dumps.
   *
   * @param bigtableOptions Bigtable Options
   * @param numberOfNodes New number of nodes in the cluster
   * @param sleepDuration How long to sleep after updating the number of nodes. Google recommends at
   *     least 20 minutes before the new nodes are fully functional
   * @throws IOException If setting up channel pool fails
   * @throws InterruptedException If sleep fails
   */
  public static void updateNumberOfBigtableNodes(
      final BigtableOptions bigtableOptions, final int numberOfNodes, final Duration sleepDuration)
      throws IOException, InterruptedException {
    updateNumberOfBigtableNodes(
        bigtableOptions, numberOfNodes, sleepDuration, Collections.emptySet());
  }

  /**
   * Updates all clusters within the specified Bigtable instance to a specified number of nodes.
   * Useful for increasing the number of nodes at the beginning of a job and decreasing it at the
   * end to lower costs yet still get high throughput during bulk ingests/dumps.
   *
   * @param bigtableOptions Bigtable Options
   * @param numberOfNodes New number of nodes in the cluster
   * @param sleepDuration How long to sleep after updating the number of nodes. Google recommends at
   *     least 20 minutes before the new nodes are fully functional
   * @param clusterNames Names of clusters to be updated, all if empty
   * @throws IOException If setting up channel pool fails
   * @throws InterruptedException If sleep fails
   */
  public static void updateNumberOfBigtableNodes(
      final BigtableOptions bigtableOptions,
      final int numberOfNodes,
      final Duration sleepDuration,
      final Set<String> clusterNames)
      throws IOException, InterruptedException {
    final ChannelPool channelPool = ChannelPoolCreator.createPool(bigtableOptions);

    try {
      final BigtableInstanceClient bigtableInstanceClient =
          new BigtableInstanceGrpcClient(channelPool);

      final String instanceName = bigtableOptions.getInstanceName().toString();

      // Fetch clusters in Bigtable instance
      final ListClustersRequest clustersRequest =
          ListClustersRequest.newBuilder().setParent(instanceName).build();
      final ListClustersResponse clustersResponse =
          bigtableInstanceClient.listCluster(clustersRequest);
      final List<Cluster> clustersToUpdate =
          clusterNames.isEmpty()
              ? clustersResponse.getClustersList()
              : clustersResponse.getClustersList().stream()
                  .filter(c -> clusterNames.contains(shorterName(c.getName())))
                  .collect(Collectors.toList());

      // For each cluster update the number of nodes
      for (Cluster cluster : clustersToUpdate) {
        final Cluster updatedCluster =
            Cluster.newBuilder().setName(cluster.getName()).setServeNodes(numberOfNodes).build();
        LOG.info("Updating number of nodes to {} for cluster {}", numberOfNodes, cluster.getName());
        bigtableInstanceClient.updateCluster(updatedCluster);
      }

      // Wait for the new nodes to be provisioned
      if (sleepDuration.getMillis() > 0) {
        LOG.info("Sleeping for {} after update", formatter.print(sleepDuration.toPeriod()));
        Thread.sleep(sleepDuration.getMillis());
      }
    } finally {
      channelPool.shutdownNow();
    }
  }

  /**
   * Get size of all clusters for specified Bigtable instance.
   *
   * @param projectId GCP projectId
   * @param instanceId Bigtable instanceId
   * @return map of clusterId to its number of nodes
   * @throws IOException If setting up channel pool fails
   * @throws GeneralSecurityException If security-related exceptions occurs
   */
  public static Map<String, Integer> getClusterSizes(
      final String projectId, final String instanceId)
      throws IOException, GeneralSecurityException {
    try (BigtableClusterUtilities clusterUtil =
        BigtableClusterUtilities.forInstance(projectId, instanceId)) {
      return Collections.unmodifiableMap(
          clusterUtil.getClusters().getClustersList().stream()
              .collect(
                  Collectors.toMap(
                      cn -> cn.getName().substring(cn.getName().indexOf("/clusters/") + 10),
                      Cluster::getServeNodes)));
    }
  }

  static String shorterName(String name) {
    if (name.lastIndexOf('/') != -1) {
      return name.substring(name.lastIndexOf('/') + 1, name.length());
    } else {
      return name;
    }
  }
}
