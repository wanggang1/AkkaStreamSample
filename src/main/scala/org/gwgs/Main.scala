package org.gwgs

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import org.gwgs.stream._
//import org.gwgs.http.server.highlevel.Overview
//import org.gwgs.http.server.lowlever.HttpServerLowLevelAPI



object Main {
  
  def main(args: Array[String]): Unit = {
  
    implicit val system = ActorSystem("akka-stream", ConfigFactory.load().getConfig("akka"))
    implicit val materializer = ActorMaterializer()
    implicit val ec = system.dispatcher

/////////////////// Akka Stream ////////////////////////////////////////////////
//    QuickStart.run
    
//    Basics.basic
//    Basics.wireup
//    Basics.fusion
    //Basics.combine

//    Graphs.graph
//    Graphs.graphReuse
//    Graphs.partialGraph
//    Graphs.simpleSource
//    Graphs.simpleFlow
//    Graphs.simplifedAPI
//    Graphs.customizeShape
//    Graphs.bidiFlow
//    Graphs.materializedValue
    
//    Modularity.runnableGraph
    //Modularity.partialGraph

//    Buffers.sample
//    Buffers.internalBuffers
    
//    CustomProcessing.graphStage
//    CustomProcessing.pushPull

//    ActorIntegration.processWithActor
    
//    ExternalIntegration.externalOrdered
//    ExternalIntegration.externalUnOrdered
//    ExternalIntegration.externalBlocking
//    ExternalIntegration.externalActor
//    ExternalIntegration.ordered
//    ExternalIntegration.unOrdered

//    ReactiveStreamIntegration.otherStream
    
//    FailureHandling.defaultToStop
//    FailureHandling.overrideWithResume
//    FailureHandling.overrideInFlow
//    FailureHandling.overrideWithRestart
//    FailureHandling.mapAsyncExternalFailure

//    StreamIO.tcpServer
//    StreamIO.replClient
//    StreamIO.replClient2
//    StreamIO.fileIO
    
    TestStream.testBuildIns
    TestStream.testViaAkkaTestkit1
    TestStream.testViaAkkaTestkit2
    TestStream.testViaAkkaTestkit3
    TestStream.testViaStreamTestkit1
    TestStream.testViaStreamTestkit2
    TestStream.testViaStreamTestkit3
    TestStream.testViaStreamTestkit4
////////////////////////////////////////////////////////////////////////////////    

////////////////// Akka HTTP ///////////////////////////////////////////////////
//    HttpServerLowLevelAPI.start
//    HttpServerLowLevelAPI.startWithHandler

    // Http Server High Level APIs
//    Overview.simpleMain
//    Overview.handleBindFailure
    
////////////////////////////////////////////////////////////////////////////////
    
    sys.addShutdownHook {
      system.log.info("Shutting down...")
      system.terminate()
      system.log.info(s"Actor system '${system.name}' successfully shut down")
    }

    //always return a Unit last, to prevent something from accidentally returned
    system.log.info("Running.....CTL+c to terminate")
  }
  
}
