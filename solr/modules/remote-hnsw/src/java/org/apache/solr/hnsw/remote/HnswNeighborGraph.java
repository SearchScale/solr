/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.hnsw.remote;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.lucene.util.hnsw.HnswGraph;

/** HNSW adjacency in vector-ordinal space. Solr applies Lucene segment headers later. */
public final class HnswNeighborGraph {
  private final int maxConn;
  private final int size;
  private final int entryNode;
  private final int[][] levelNodes;
  private final int[][][] neighbors;

  public HnswNeighborGraph(
      int maxConn, int size, int entryNode, int[][] levelNodes, int[][][] neighbors) {
    this.maxConn = maxConn;
    this.size = size;
    this.entryNode = entryNode;
    this.levelNodes = levelNodes;
    this.neighbors = neighbors;
  }

  public static HnswNeighborGraph empty(int maxConn) {
    return new HnswNeighborGraph(maxConn, 0, 0, new int[0][], new int[0][][]);
  }

  public static HnswNeighborGraph singleNode(int maxConn) {
    return new HnswNeighborGraph(
        maxConn, 1, 0, new int[][] {null}, new int[][][] {new int[][] {new int[0]}});
  }

  public static HnswNeighborGraph fromHnswGraph(HnswGraph graph) throws IOException {
    int levels = graph.numLevels();
    int size = graph.size();
    int[][] levelNodes = new int[levels][];
    int[][][] neighbors = new int[levels][][];
    for (int level = 0; level < levels; level++) {
      int[] nodes = sortedNodes(graph.getNodesOnLevel(level));
      if (level == 0) {
        levelNodes[level] = null;
        neighbors[level] = new int[size][];
        for (int node = 0; node < size; node++) {
          neighbors[level][node] = collectNeighbors(graph, level, node);
        }
      } else {
        levelNodes[level] = nodes;
        neighbors[level] = new int[nodes.length][];
        for (int i = 0; i < nodes.length; i++) {
          neighbors[level][i] = collectNeighbors(graph, level, nodes[i]);
        }
      }
    }
    return new HnswNeighborGraph(graph.maxConn(), size, graph.entryNode(), levelNodes, neighbors);
  }

  private static int[] sortedNodes(HnswGraph.NodesIterator nodesOnLevel) {
    int[] nodes = new int[nodesOnLevel.size()];
    for (int n = 0; nodesOnLevel.hasNext(); n++) {
      nodes[n] = nodesOnLevel.nextInt();
    }
    Arrays.sort(nodes);
    return nodes;
  }

  private static int[] collectNeighbors(HnswGraph graph, int level, int node) throws IOException {
    graph.seek(level, node);
    List<Integer> collected = new ArrayList<>();
    for (int n = graph.nextNeighbor(); n != NO_MORE_DOCS; n = graph.nextNeighbor()) {
      collected.add(n);
    }
    int[] result = new int[collected.size()];
    for (int i = 0; i < collected.size(); i++) {
      result[i] = collected.get(i);
    }
    return result;
  }

  public int maxConn() {
    return maxConn;
  }

  public int size() {
    return size;
  }

  public int entryNode() {
    return entryNode;
  }

  public int numLevels() {
    return neighbors.length;
  }

  public int[] nodesOnLevel(int level) {
    if (level == 0) {
      int[] nodes = new int[size];
      for (int i = 0; i < size; i++) {
        nodes[i] = i;
      }
      return nodes;
    }
    return levelNodes[level];
  }

  public int[] neighbors(int level, int nodeIndex) {
    return neighbors[level][nodeIndex];
  }

  int[][] rawLevelNodes() {
    return levelNodes;
  }

  int[][][] rawNeighbors() {
    return neighbors;
  }
}
