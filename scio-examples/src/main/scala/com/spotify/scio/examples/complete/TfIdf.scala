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

package com.spotify.scio.examples.complete

import java.nio.channels.Channels

import com.spotify.scio._
import com.spotify.scio.examples.common.ExampleData
import com.spotify.scio.values.SCollection
import org.apache.beam.sdk.io.FileSystems

import scala.collection.JavaConverters._
import scala.io.Source

/*
SBT
runMain
  com.spotify.scio.examples.complete.TfIdf
  --project=[PROJECT] --runner=DataflowPRunner --zone=[ZONE]
  --input=gs://apache-beam-samples/shakespeare/?*.txt
  --output=gs://[BUCKET]/[PATH]/tf_idf
*/

object TfIdf {

  def main(cmdlineArgs: Array[String]): Unit = {
    val (sc, args) = ContextAndArgs(cmdlineArgs)

    FileSystems.setDefaultPipelineOptions(sc.options)

    val uris = FileSystems
      .`match`(args.getOrElse("input", ExampleData.SHAKESPEARE_ALL))
      .metadata().asScala.map(_.resourceId().toString)

    val uriToContent = args.getOrElse("mode", "union") match {
      case "union" =>
        // Read each file separately with `sc.textFile`, key by URI, and then merge with
        // `SCollection.unionAll`. This works well for a small list of files. It can also handle
        // large individual files as each `sc.textFile` can be dynamically split by the runner.
        SCollection.unionAll(uris.map(uri => sc.textFile(uri).keyBy(_ => uri)))
      case "fs" =>
        // Create a single `SCollection[String]` of URIs, and read files on workers in `.map` and
        // then key by URI. This works well for a large list of files. However each file is read by
        // one worker thread and cannot be further split. Skew in file sizes may lead to unbalanced
        // work load among workers.
        sc.parallelize(uris)
          .flatMap { uri =>
            val rsrc = FileSystems.matchSingleFileSpec(uri).resourceId()
            val in = Channels.newInputStream(FileSystems.open(rsrc))
            Source.fromInputStream(in)
              .getLines()
              .map((uri, _))
          }
      // TODO: add splittable DoFn approach once available
      case m => throw new IllegalArgumentException(s"Unkonwn mode: $m")
    }

    computeTfIdf(uriToContent)
      .map { case (t, (d, tfIdf)) =>
        s"$t\t$d\t$tfIdf"
      }
      .saveAsTextFile(args("output"))

    sc.close()
  }

  def computeTfIdf(uriToContent: SCollection[(String, String)])
  : SCollection[(String, (String, Double))] = {
    val uriToWords = uriToContent.flatMap { case (uri, line) =>
      line.split("\\W+").filter(_.nonEmpty).map(w => (uri, w.toLowerCase))
    }  // (d, t)

    val uriToWordAndCount = uriToWords
      .countByValue  // ((d, t), tf)
      .map(t => (t._1._1, (t._1._2, t._2)))  // (d, (t, tf))

    val wordToDf = uriToWords.distinct.values.countByValue  // (t, df)
      .cross(uriToContent.keys.distinct.count)  // N
      .map { case ((t, df), numDocs) => (t, df.toDouble / numDocs) }  // (t, df/N)

    uriToWords.keys.countByValue  // (d, |d|)
      .join(uriToWordAndCount)  // (d, (|d|, (t, tf)))
      .map { case (d, (dl, (t, tf))) => (t, (d, tf.toDouble / dl)) }  // (t, (d, tf/|d|))
      .join(wordToDf)  // (t, ((d, tf/|d|), df/N))
      .map { case (t, ((d, tf), df)) =>
        (t, (d, tf * math.log(1 / df)))
      }
  }

}
