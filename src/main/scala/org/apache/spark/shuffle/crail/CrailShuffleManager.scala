/*
 * Spark-IO: Fast storage and network I/O for Spark
 *
 * Author: Patrick Stuedi <stu@zurich.ibm.com>
 *
 * Copyright (C) 2016, IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.spark.shuffle.crail

import java.util.concurrent.atomic.AtomicBoolean

import org.apache.spark._
import org.apache.spark.network.buffer.ManagedBuffer
import org.apache.spark.shuffle._
import org.apache.spark.storage.{ShuffleBlockId, CrailStore}
import org.apache.spark.common._
import org.apache.spark.util.Utils


/**
 * A ShuffleManager using Peregrine, that creates one output file per reduce partition on each
 * mapper (possibly reusing these across waves of tasks).
 */
private[spark] class CrailShuffleManager(conf: SparkConf) extends ShuffleManager with Logging {

  logInfo("rdfs shuffle started")

  private val fileShuffleBlockManager = new CrailShuffleBlockResolver(conf)
//  private lazy val blockManager = SparkEnv.get.blockManager
  private var intialized = new AtomicBoolean(false)

  lazy val shuffleSorterClass =
    conf.get("spark.crail.shuffle.sorter", "org.apache.spark.shuffle.crail.CrailSparkShuffleSorter")
  lazy val shuffleSerializerClass =
    conf.get("spark.crail.shuffle.serializer", "org.apache.spark.shuffle.crail.CrailSparkShuffleSerializer")
  lazy val shuffleSorter =
    Utils.classForName(shuffleSorterClass).newInstance.asInstanceOf[CrailShuffleSorter]
  lazy val shuffleSerializer =
    Utils.classForName(shuffleSerializerClass).newInstance.asInstanceOf[CrailShuffleSerializer]

  /* Register a shuffle with the manager and obtain a handle for it to pass to tasks. */
  override def registerShuffle[K, V, C](
      shuffleId: Int,
      numMaps: Int,
      dependency: ShuffleDependency[K, V, C]): ShuffleHandle = {
    CrailStore.get.registerShuffle(shuffleId, numMaps, dependency.partitioner.numPartitions)
    new BaseShuffleHandle(shuffleId, numMaps, dependency)
  }  
  
  /**
   * Get a reader for a range of reduce partitions (startPartition to endPartition-1, inclusive).
   * Called on executors by reduce tasks.
   */
  override def getReader[K, C](
      handle: ShuffleHandle,
      startPartition: Int,
      endPartition: Int,
      context: TaskContext): ShuffleReader[K, C] = {
    if (intialized.compareAndSet(false, true)){
      logInfo("loading shuffler serializer " + shuffleSerializerClass)
      logInfo("loading shuffler sorter " + shuffleSorterClass)
    }
    new RdfsShuffleReader(handle.asInstanceOf[BaseShuffleHandle[K, _, C]],
      startPartition, endPartition, context, shuffleSerializer, shuffleSorter)
  }  
  
  /** Get a writer for a given partition. Called on executors by map tasks. */
  override def getWriter[K, V](handle: ShuffleHandle, mapId: Int, context: TaskContext)
      : ShuffleWriter[K, V] = {
    new CrailShuffleWriter(shuffleBlockResolver, handle.asInstanceOf[BaseShuffleHandle[K, V, _]],
      mapId, context, shuffleSerializer)
  }
  
  /** Remove a shuffle's metadata from the ShuffleManager. */
  override def unregisterShuffle(shuffleId: Int): Boolean = {
    CrailStore.get.unregisterShuffle(shuffleId)
    true
  }  
  
  override def shuffleBlockResolver: CrailShuffleBlockResolver = {
    fileShuffleBlockManager
  } 
  
  /** Shut down this ShuffleManager. */
  override def stop(): Unit = {
    logInfo("shutting shuffle manager")
    CrailStore.put
  }  
  
  //----
  
}

private object CrailShuffleManager extends Logging {

}

class CrailShuffleBlockResolver (conf: SparkConf)
  extends ShuffleBlockResolver with Logging {

  override def getBlockData(blockId: ShuffleBlockId): ManagedBuffer = {
    null
  }

  override def stop() {
  }
}
