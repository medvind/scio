/*
 * Copyright 2024 Spotify AB.
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

package com.spotify.scio.smb

import com.spotify.scio.ScioContext
import com.spotify.scio.avro.{Account, AccountStatus, Address, User}
import com.spotify.scio.testing.PipelineSpec
import org.apache.beam.sdk.Pipeline.PipelineExecutionException
import org.apache.beam.sdk.extensions.smb.BucketMetadata.HashType
import org.apache.beam.sdk.extensions.smb.AvroSortedBucketIO
import org.apache.beam.sdk.values.TupleTag

import scala.jdk.CollectionConverters._
import org.slf4j.LoggerFactory

import java.nio.file.Files
import scala.util.Try

class SmbJoinFallbackTest extends PipelineSpec {
  private val logger = LoggerFactory.getLogger(classOf[SmbJoinFallbackTest])

  "SortedBucketScioContext.sortMergeJoin" should "fall back to regular join if hash types are " +
    "incompatible" in {

    val tmpDir = Files.createTempDirectory("smb-version-test-mixed-avro-read").toFile
    tmpDir.deleteOnExit()

    val output1 = tmpDir.toPath.resolve("output1")
    val output2 = tmpDir.toPath.resolve("output2")

    val writes = Seq(
      AvroSortedBucketIO
      .write(classOf[Integer], "id", classOf[User])
      .to(output1.toString)
      .withNumBuckets(1)
      .withNumShards(1)
      .withHashType(HashType.MURMUR3_32),
      AvroSortedBucketIO
      .write(classOf[Integer], "id", classOf[User])
      .to(output2.toString)
      .withNumBuckets(1)
      .withNumShards(1)
      .withHashType(HashType.ICEBERG))

    val accounts = (1 to 10).map { i =>
      Account
        .newBuilder()
        .setId(i)
        .setName(i.toString)
        .setAmount(i.toDouble)
        .setType(s"type$i")
        .setAccountStatus(AccountStatus.Active)
        .build()
    }

    val defaultAddress = Address
      .newBuilder()
      .setStreet1("Birger Jarlsgatan")
      .setStreet2("")
      .setZip("12345")
      .setCity("Stockholm")
      .setState("")
      .setCountry("Sweden")
      .build()

    val users = (1 to 10).map { i =>
      User
        .newBuilder()
        .setId(i)
        .setFirstName("Abcd" + i)
        .setLastName("Efgh" + i)
        .setEmail("something@example.com")
        .setAccounts(Seq(accounts(i-1)).asJava)
        .setAddress(defaultAddress)
        .build()
    }

    // Write data
    {
      val sc = ScioContext()
      val records = sc.parallelize(users)
      writes.foreach(records.saveAsSortedBucket(_))

      sc.run()
      ()
    }

    logger.info("Writing is done. Reading!")

    def readAndJoin = {
      Try {
        val read1 = AvroSortedBucketIO
          .read(new TupleTag[User]("user1"), classOf[User])
          .from(output1.toString)
        val read2 = AvroSortedBucketIO
          .read(new TupleTag[User]("user2"), classOf[User])
          .from(output2.toString)

        val sc = ScioContext()

        val tap = sc.sortMergeJoin(classOf[Integer], read1, read2)
          .count
          .materialize

        val result = tap
          .get(sc.run().waitUntilDone())
          .value
          .toSeq
          .head

        logger.info("Found " + result.toString + " records.")
      }
    }

    val result = readAndJoin
    result.failed.get shouldBe a[PipelineExecutionException]
    result.failed.get.getMessage should startWith ("java.lang.IllegalStateException")
  }



}
