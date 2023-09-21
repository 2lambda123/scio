/*
 * Copyright 2019 Spotify AB.
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

package com.spotify.scio.examples.extra

import cats.Eq
import com.google.cloud.bigtable.hbase.adapters.read.RowCell
import com.spotify.scio.bigtable._
import com.spotify.scio.io._
import com.spotify.scio.testing._
import org.apache.hadoop.hbase.Cell
import org.apache.hadoop.hbase.client.{Mutation, Result}

import java.nio.charset.StandardCharsets
import java.util.Collections
import scala.collection.immutable.Seq

class BigtableExampleTest extends PipelineSpec {
  import BigtableExample._

  val bigtableOptions: Seq[String] = Seq(
    "--bigtableProjectId=my-project",
    "--bigtableInstanceId=my-instance",
    "--bigtableTableId=my-table"
  )

  val textIn: Seq[String] = Seq("a b c d e", "a b a b")
  val wordCount: Seq[(String, Long)] = Seq("a" -> 3L, "b" -> 3L, "c" -> 1L, "d" -> 1L, "e" -> 1L)

  "BigtableWriteExample" should "work" in {
    val expectedMutations: Seq[Mutation] = wordCount
      .map { case (word, count) => BigtableExample.toPutMutation(word, count) }
    implicit val eqMutation: Eq[Mutation] = Eq.by(_.toString)

    JobTest[com.spotify.scio.examples.extra.BigtableWriteExample.type]
      .args(bigtableOptions :+ "--input=in.txt": _*)
      .input(TextIO("in.txt"), textIn)
      .output(BigtableIO[Mutation]("my-project", "my-instance", "my-table")) {
        _ should containInAnyOrder(expectedMutations)
      }
      .run()
  }

  "BigtableReadExample" should "work" in {
    def toResult(key: String, value: Long): Result = {
      val cell = new RowCell(
        key.getBytes(StandardCharsets.UTF_8),
        FAMILY_NAME,
        COLUMN_QUALIFIER,
        0L,
        BigInt(value).toByteArray
      )
      Result.create(Collections.singletonList[Cell](cell))
    }

    val results: Seq[Result] = wordCount.map { case (word, count) => toResult(word, count) }
    val expectedText: Seq[String] = wordCount.map { case (word, count) => s"$word:$count" }

    JobTest[com.spotify.scio.examples.extra.BigtableReadExample.type]
      .args(bigtableOptions :+ "--output=out.txt": _*)
      .input(BigtableIO("my-project", "my-instance", "my-table"), results)
      .output(TextIO("out.txt"))(coll => coll should containInAnyOrder(expectedText))
      .run()
  }
}
