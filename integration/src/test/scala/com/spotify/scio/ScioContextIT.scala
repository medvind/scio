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

package com.spotify.scio

import com.spotify.scio.testing.util.ItUtils
import com.spotify.scio.util.ScioUtil
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions
import org.apache.beam.runners.dataflow.{DataflowPipelineTranslator, DataflowRunner}
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions
import org.apache.beam.sdk.io.FileSystems
import org.apache.beam.sdk.options.{
  PipelineOptions,
  PipelineOptionsFactory,
  PortablePipelineOptions
}
import org.apache.beam.sdk.util.construction.{Environments, PipelineTranslation, SdkComponents}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import scala.jdk.CollectionConverters._

class ScioContextIT extends AnyFlatSpec with Matchers {
  "ScioContext" should "have temp location for DataflowRunner" in {
    val opts = PipelineOptionsFactory.create()
    opts.setRunner(classOf[DataflowRunner])
    opts.as(classOf[GcpOptions]).setProject(ItUtils.project)
    verify(opts)
  }

  it should "support user defined temp location for DataflowRunner" in {
    val opts = PipelineOptionsFactory.create()
    opts.setRunner(classOf[DataflowRunner])
    opts.as(classOf[GcpOptions]).setProject(ItUtils.project)
    opts.setTempLocation(ItUtils.gcpTempLocation("scio-context-it"))
    verify(opts)
  }

  private def verify(options: PipelineOptions): Unit = {
    val sc = ScioContext(options)
    val gcpTempLocation = sc.optionsAs[GcpOptions].getGcpTempLocation
    val tempLocation = sc.options.getTempLocation

    tempLocation should not be null
    gcpTempLocation should not be null
    tempLocation shouldBe gcpTempLocation
    ScioUtil.isRemoteUri(new URI(gcpTempLocation)) shouldBe true
    ()
  }

  it should "register remote file systems in the test context" in {
    val sc = ScioContext.forTest()
    noException shouldBe thrownBy {
      FileSystems.matchSingleFileSpec("gs://data-integration-test-eu/shakespeare.json")
    }
    sc.run()
  }

  it should "#1734: generate a reasonably sized job graph" in {
    val opts = PipelineOptionsFactory.create()
    opts.setRunner(classOf[DataflowRunner])
    opts.as(classOf[GcpOptions]).setProject(ItUtils.project)
    opts.as(classOf[DataflowPipelineOptions]).setRegion(ItUtils.Region)
    val sc = ScioContext(opts)
    sc.parallelize(1 to 100)
    val runner = DataflowRunner.fromOptions(sc.options)
    val sdkComponents = SdkComponents.create();
    sdkComponents.registerEnvironment(
      Environments
        .createOrGetDefaultEnvironment(opts.as(classOf[PortablePipelineOptions]))
        .toBuilder()
        .addAllCapabilities(Environments.getJavaCapabilities())
        .build()
    )
    val pipelineProto = PipelineTranslation.toProto(sc.pipeline, sdkComponents, true)
    val jobSpecification =
      runner.getTranslator.translate(
        sc.pipeline,
        pipelineProto,
        sdkComponents,
        runner,
        Nil.asJava
      )
    val newJob = jobSpecification.getJob()
    val graph = DataflowPipelineTranslator.jobToString(newJob)

    import com.fasterxml.jackson.databind.ObjectMapper
    val objectMapper = new ObjectMapper()
    val rootNode = objectMapper.readTree(graph)
    val path = "/steps/0/properties/output_info/0/encoding/component_encodings/0/@type"
    val coder = rootNode.at(path).asText
    coder should not(equal("org.apache.beam.sdk.coders.CustomCoder"))
  }
}
