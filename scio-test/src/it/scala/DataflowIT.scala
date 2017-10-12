/*
 * Copyright 2017 Spotify AB.
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

import com.spotify.scio._
import com.spotify.scio.runners.dataflow._
import org.scalatest._

import scala.collection.JavaConverters._

object DataflowIT {
  val projectId = "scio-playground"

  def run(): ScioResult = {
    val (sc, _) = ContextAndArgs(Array(s"--project=$projectId", "--runner=DataflowRunner"))
    val c = ScioMetrics.counter("count")
    sc.parallelize(1 to 100)
      .map { x =>
        c.inc()
        x
      }
      .filter { x =>
        c.inc()
        true
      }
    sc.close().waitUntilDone()
  }
}

class DataflowIT extends FlatSpec with Matchers {

  private val scioResult = DataflowIT.run()
  private val dfResult = scioResult.as[DataflowResult]

  "DataflowResult" should "have Dataflow data" in {
    dfResult.internal.getState shouldBe scioResult.state
    dfResult.getJob.getProjectId shouldBe DataflowIT.projectId
    dfResult.getJobMetrics.getMetrics.asScala should not be empty
  }

  it should "round trip ScioResult" in {
    val r = dfResult.asScioResult
    r.state shouldBe scioResult.state
    r.getMetrics shouldBe scioResult.getMetrics
    r.allCountersAtSteps shouldBe scioResult.allCountersAtSteps
  }

  it should "work independently" in {
    val r = DataflowResult(dfResult.internal.getProjectId, dfResult.internal.getJobId)
    r.getJob.getProjectId shouldBe dfResult.internal.getProjectId
    r.getJobMetrics.getMetrics.asScala should not be empty
    r.asScioResult.state shouldBe scioResult.state
    r.asScioResult.getMetrics shouldBe scioResult.getMetrics
    r.asScioResult.allCountersAtSteps shouldBe scioResult.allCountersAtSteps
  }

}
