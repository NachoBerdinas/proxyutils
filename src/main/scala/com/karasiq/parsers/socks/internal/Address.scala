package com.karasiq.parsers.socks.internal

import java.net.{InetAddress, InetSocketAddress}

import akka.util.ByteString
import com.karasiq.parsers.socks.SocksClient.SocksVersion
import com.karasiq.parsers.{ByteFragment, ByteRange}

private[socks] object Address {
  sealed trait AddressType {
    def code: Byte
  }

  object AddressType extends ByteRange[AddressType] {
    case object IPv4Address extends AddressType {
      override def code: Byte = 0x01
    }
    case object DomainName extends AddressType {
      override def code: Byte = 0x03
    }
    case object IPv6Address extends AddressType {
      override def code: Byte = 0x04
    }

    override def fromByte: PartialFunction[Byte, AddressType] = {
      case 0x01 ⇒ IPv4Address
      case 0x03 ⇒ DomainName
      case 0x04 ⇒ IPv6Address
    }

    override def toByte: PartialFunction[AddressType, Byte] = {
      case addressType ⇒
        addressType.code
    }
  }

  @inline
  private def readIP(b: Seq[Byte]): InetAddress = InetAddress.getByAddress(b.toArray)

  private object IPv4 extends ByteFragment[InetAddress] {
    override def toBytes(address: InetAddress): ByteString = {
      assert(address.getAddress.length == 4, s"Not an IPv4 address: $address")
      ByteString(address.getAddress)
    }

    override def fromBytes: Extractor = {
      case bytes if bytes.length >= 4 ⇒
        readIP(bytes.take(4)) → bytes.drop(4)
    }
  }

  private object IPv6 extends ByteFragment[InetAddress] {
    override def toBytes(address: InetAddress) = {
      assert(address.getAddress.length == 16, s"Not an IPv6 address: $address")
      ByteString(address.getAddress)
    }

    override def fromBytes: Extractor = {
      case bytes if bytes.length >= 16 ⇒
        readIP(bytes.take(16)) → bytes.drop(16)
    }
  }

  object V4 extends ByteFragment[InetSocketAddress] {
    override def toBytes(address: InetSocketAddress): ByteString = {
      IPv4(InetAddress.getByName(address.getHostString)) ++ Port(address.getPort)
    }

    override def fromBytes: Extractor = {
      case Port(port, Socks4AInvalidIP(_, rest @ NullTerminatedString(_, NullTerminatedString(domain, _)))) ⇒ // SOCKS4A
        InetSocketAddress.createUnresolved(domain, port) → rest

      case Port(port, IPv4(address, rest)) ⇒ // SOCKS4
        new InetSocketAddress(address, port) → rest
    }
  }

  object V5 extends ByteFragment[InetSocketAddress] {
    import AddressType._

    override def toBytes(address: InetSocketAddress): ByteString = {
      val addr: ByteString = if (address.isUnresolved) {
        ByteString(AddressType.DomainName.code) ++ LengthString(address.getHostString)
      } else address.getAddress match { // IP address
        case a if a.getAddress.length == 16 ⇒
          ByteString(AddressType.IPv6Address.code) ++ IPv6(a)
        case a if a.getAddress.length == 4 ⇒
          ByteString(AddressType.IPv4Address.code) ++ IPv4(a)
      }

      addr ++ Port(address.getPort)
    }

    override def fromBytes: Extractor = {
      case AddressType(IPv4Address) +: (IPv4(address, Port(port, rest))) ⇒
        new InetSocketAddress(address, port) → rest

      case AddressType(IPv6Address) +: (IPv6(address, Port(port, rest))) ⇒
        new InetSocketAddress(address, port) → rest

      case AddressType(DomainName) +: (LengthString(host, Port(port, rest))) ⇒
        InetSocketAddress.createUnresolved(host, port) → rest
    }
  }

  def apply(version: SocksVersion, address: InetSocketAddress): ByteString = version match {
    case SocksVersion.SocksV4 ⇒ this.V4(address)
    case SocksVersion.SocksV5 ⇒ this.V5(address)
  }
}
