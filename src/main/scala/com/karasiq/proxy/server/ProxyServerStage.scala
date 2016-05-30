package com.karasiq.proxy.server

import java.io.IOException

import akka.NotUsed
import akka.http.scaladsl.HttpsConnectionContext
import akka.stream.TLSProtocol.{SendBytes, SessionBytes, SslTlsInbound}
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.util.ByteString
import com.karasiq.networkutils.http.HttpStatus
import com.karasiq.parsers.http.{HttpConnect, HttpRequest, HttpResponse}
import com.karasiq.parsers.socks.SocksClient.SocksVersion.{SocksV4, SocksV5}
import com.karasiq.parsers.socks.SocksClient._
import com.karasiq.parsers.socks.SocksServer.{AuthMethodResponse, _}
import com.karasiq.proxy.ProxyException

import scala.concurrent.{Future, Promise}

object ProxyServerStage {
  def successResponse(request: ProxyConnectionRequest): ByteString = {
    request.scheme match {
      case "http" ⇒
        HttpResponse((HttpStatus(200, "Connection established"), Nil))

      case "socks" ⇒
        ConnectionStatusResponse(SocksV5, None, Codes.success(SocksV5))

      case "socks4" ⇒
        ConnectionStatusResponse(SocksV4, None, Codes.success(SocksV4))

      case _ ⇒
        throw new IllegalArgumentException(s"Invalid proxy connection request: $request")
    }
  }

  def failureResponse(request: ProxyConnectionRequest): ByteString = {
    request.scheme match {
      case "http" ⇒
        HttpResponse((HttpStatus(400, "Bad Request"), Nil))

      case "socks" ⇒
        ConnectionStatusResponse(SocksV5, None, Codes.failure(SocksV5))

      case "socks4" ⇒
        ConnectionStatusResponse(SocksV4, None, Codes.failure(SocksV4))

      case _ ⇒
        throw new IllegalArgumentException(s"Invalid proxy connection request: $request")
    }
  }

  def withTls(tlsContext: HttpsConnectionContext): Flow[ByteString, ByteString, Future[(ProxyConnectionRequest, Flow[ByteString, ByteString, NotUsed])]] = {
    Flow.fromGraph(GraphDSL.create(new ProxyServerStage) { implicit builder ⇒ stage ⇒
      import GraphDSL.Implicits._
      val tlsInbound = builder.add(Flow[SslTlsInbound].collect { case SessionBytes(_, bytes) ⇒ bytes })
      val tlsOutbound = builder.add(Flow[ByteString].map(SendBytes(_)))
      val tls = builder.add(TLS(tlsContext.sslContext, tlsContext.firstSession, TLSRole.server, TLSClosing.eagerClose))
      tls.out2 ~> tlsInbound ~> stage
      stage ~> tlsOutbound ~> tls.in1
      FlowShape(tls.in2, tls.out1)
    })
  }
}

class ProxyServerStage extends GraphStageWithMaterializedValue[FlowShape[ByteString, ByteString], Future[(ProxyConnectionRequest, Flow[ByteString, ByteString, NotUsed])]] {
  val tcpInput = Inlet[ByteString]("ProxyServer.tcpIn")
  val tcpOutput = Outlet[ByteString]("ProxyServer.tcpOut")
  val promise = Promise[(ProxyConnectionRequest, Flow[ByteString, ByteString, NotUsed])]()

  val shape = new FlowShape(tcpInput, tcpOutput)

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val logic = new GraphStageLogic(shape) {
      val bufferSize = 8192
      var buffer = ByteString.empty

      override def postStop() = {
        promise.tryFailure(new IOException("Connection closed"))
        super.postStop()
      }

      override def preStart() = {
        super.preStart()
        pull(tcpInput)
      }

      def writeBuffer(data: ByteString): Unit = {
        if (buffer.length > bufferSize) {
          failStage(BufferOverflowException("Buffer overflow"))
        } else {
          buffer ++= data
        }
      }

      def emitRequest(request: ProxyConnectionRequest): Unit = {
        val inlet = new SubSinkInlet[ByteString]("ProxyServer.tcpInConnected")
        val outlet = new SubSourceOutlet[ByteString]("ProxyServer.tcpOutConnected")
        setHandler(tcpInput, new InHandler {
          def onPush() = {
            val data = grab(tcpInput)
            if (outlet.isAvailable) {
              outlet.push(buffer ++ data)
              buffer = ByteString.empty
            } else {
              buffer ++= data
            }
          }

          override def onUpstreamFinish() = {
            if (buffer.isEmpty) {
              outlet.complete()
            }
          }

          override def onUpstreamFailure(ex: Throwable) = {
            outlet.fail(ex)
            super.onUpstreamFailure(ex)
          }
        })

        setHandler(tcpOutput, new OutHandler {
          def onPull() = {
            if (!inlet.hasBeenPulled) {
              inlet.pull()
            }
          }

          override def onDownstreamFinish() = {
            inlet.cancel()
          }
        })

        outlet.setHandler(new OutHandler {
          def onPull() = {
            if (isClosed(tcpInput)) {
              if (buffer.nonEmpty) {
                outlet.push(buffer)
                buffer = ByteString.empty
              }
              outlet.complete()
            } else if (buffer.nonEmpty) {
              outlet.push(buffer)
              buffer = ByteString.empty
            } else {
              pull(tcpInput)
            }
          }
        })

        inlet.setHandler(new InHandler {
          def onPush() = {
            emit(tcpOutput, inlet.grab())
          }
        })

        promise.success(request → Flow.fromSinkAndSource(inlet.sink, outlet.source))
        inlet.pull()
      }

      def processBuffer(): Unit = {
        buffer match {
          case ConnectionRequest((socksVersion, command, address, userId), rest) ⇒
            buffer = ByteString(rest:_*)
            if (command != Command.TcpConnection) {
              val code = if (socksVersion == SocksVersion.SocksV5) Codes.Socks5.COMMAND_NOT_SUPPORTED else Codes.failure(socksVersion)
              emit(tcpOutput, ConnectionStatusResponse(socksVersion, None, code), () ⇒ {
                val ex = new ProxyException("Command not supported")
                promise.failure(ex)
                failStage(ex)
              })
            } else {
              emitRequest(ProxyConnectionRequest(if (socksVersion == SocksVersion.SocksV5) "socks" else "socks4", address))
            }

          case AuthRequest(methods, rest) ⇒
            buffer = ByteString(rest:_*)
            if (methods.contains(AuthMethod.NoAuth)) {
              emit(tcpOutput, AuthMethodResponse(AuthMethod.NoAuth), () ⇒ pull(tcpInput))
            } else {
              emit(tcpOutput, AuthMethodResponse.notSupported, () ⇒ {
                val ex = new ProxyException("No valid authentication methods provided")
                promise.failure(ex)
                failStage(ex)
              })
            }

          case HttpRequest((method, url, headers), rest) ⇒
            buffer = ByteString(rest:_*)
            val address = HttpConnect.addressOf(url)
            if (address.getHostString.isEmpty) { // Plain HTTP request
              emit(tcpOutput, HttpResponse(HttpStatus(400, "Bad Request"), Nil) ++ ByteString("Request not supported"), () ⇒ {
                val ex = new ProxyException("Plain HTTP not supported")
                promise.failure(ex)
                failStage(ex)
              })
            } else {
              emitRequest(ProxyConnectionRequest("http", address))
            }

          case _ ⇒
            pull(tcpInput)
        }
      }

      setHandler(tcpInput, new InHandler {
        def onPush() = {
          val data = grab(tcpInput)
          writeBuffer(data)
          processBuffer()
        }
      })

      setHandler(tcpOutput, eagerTerminateOutput)
    }
    (logic, promise.future)
  }
}