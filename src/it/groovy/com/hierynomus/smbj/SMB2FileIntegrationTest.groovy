/*
 * Copyright (C)2016 - SMBJ Contributors
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
 */
package com.hierynomus.smbj

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileNotifyAction
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2ChangeNotifyFlags
import com.hierynomus.mssmb2.SMB2CompletionFilter
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.mssmb2.SMB2LockFlag
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.messages.submodule.SMB2LockElement
import com.hierynomus.smb.SMBPacket
import com.hierynomus.mssmb2.messages.SMB2Cancel
import com.hierynomus.mssmb2.messages.SMB2ChangeNotifyResponse
import com.hierynomus.protocol.commons.concurrent.Futures
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.io.ArrayByteChunkProvider
import com.hierynomus.smbj.io.InputStreamByteChunkProvider
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.transport.tcp.async.AsyncDirectTcpTransportFactory
import com.hierynomus.smbj.transport.tcp.direct.DirectTcpTransportFactory
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

import static com.hierynomus.mssmb2.SMB2CreateDisposition.*

class SMB2FileIntegrationTest extends Specification {

  DiskShare share
  Session session
  Connection connection
  SMBClient client

  def setup() {
    def config = SmbConfig
      .builder()
      .withMultiProtocolNegotiate(true)
      .withDialects(SMB2Dialect.SMB_3_0).withEncryptData(true)
      .withTransportLayerFactory(new DirectTcpTransportFactory<>())
      .withSecurityProvider(new BCSecurityProvider())
      .withSigningRequired(true)
      /*.withEncryptData(true)*/.build()
    client = new SMBClient(config)
    connection = client.connect("127.0.0.1")
    session = connection.authenticate(new AuthenticationContext("smbj", "smbj".toCharArray(), null))
    share = session.connectShare("user") as DiskShare
  }

  def cleanup() {
    connection.close()
  }

  def "should list contents of empty share"() {
    when:
    def list = share.list("")

    then:
    list.size() == 2
    list.get(0).fileName == "."
    list.get(1).fileName == ".."
  }

  @Unroll
  def "should create file and list contents of share"() {
    given:
    def f = share.openFile("test", EnumSet.of(AccessMask.GENERIC_ALL), null, SMB2ShareAccess.ALL, FILE_CREATE, null)
    f.close()

    expect:
    share.list(path).collect { it.fileName } contains "test"

    cleanup:
    share.rm("test")

    where:
    path << ["", null]
  }

  def "should create directory and list contents"() {
    given:
    share.mkdir("folder-1")

    expect:
    share.list("").collect { it.fileName } contains "folder-1"
    share.list("folder-1").collect { it.fileName } == [".", ".."]

    cleanup:
    share.rmdir("folder-1", true)
  }

  def "should read file contents of file in directory"() {
    given:
    share.mkdir("api")
    def textFile = share.openFile("api\\test.txt", EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, FILE_CREATE, null)
    textFile.write(new ArrayByteChunkProvider("Hello World!".getBytes(StandardCharsets.UTF_8), 0))
    textFile.close()

    when:
    def read = share.openFile("api\\test.txt", EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, FILE_OPEN, null)

    then:
    def is = read.getInputStream()
    is.readLines() == ["Hello World!"]

    cleanup:
    is?.close()
    read.close()
    share.rmdir("api", true)
  }

  def "should delete locked file"() {
    given:
    def lockedFile = share.openFile("locked", EnumSet.of(AccessMask.GENERIC_WRITE), null, EnumSet.noneOf(SMB2ShareAccess.class), FILE_CREATE, null)

    when:
    share.rm("locked")

    then:
    def e = thrown(SMBApiException.class)
    e.statusCode == NtStatus.STATUS_SHARING_VIOLATION.value
    share.list("").collect { it.fileName } contains "locked"

    cleanup:
    lockedFile.close()
    share.rm("locked")
  }

  def "should transfer big file to share"() {
    given:
    def file = share.openFile("bigfile", EnumSet.of(AccessMask.FILE_WRITE_DATA), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
    def bytes = new byte[32 * 1024 * 1024 + 10]
    Random.newInstance().nextBytes(bytes)
    def istream = new ByteArrayInputStream(bytes)

    when:
    def ostream = file.getOutputStream(new LoggingProgressListener())
    try {
      byte[] buffer = new byte[4096]
      int len
      while ((len = istream.read(buffer)) != -1) {
        ostream.write(buffer, 0, len)
      }
    } finally {
      istream.close()
      ostream.close()
      file.close()
    }

    then:
    share.fileExists("bigfile")

    when:
    def readBytes = new byte[32 * 1024 * 1024 + 10]
    def readFile = share.openFile("bigfile", EnumSet.of(AccessMask.FILE_READ_DATA), null, SMB2ShareAccess.ALL, FILE_OPEN, null)
    try {
      def remoteIs = readFile.getInputStream(new LoggingProgressListener())
      try {
        def offset = 0
        while (offset < readBytes.length) {
          def read = remoteIs.read(readBytes, offset, readBytes.length - offset)
          if (read > 0) {
            offset += read
          } else {
            break
          }
        }
      } finally {
        remoteIs.close()
      }
    } finally {
      readFile.close()
    }

    then:
    readBytes == bytes

    cleanup:
    share.rm("bigfile")
  }

  def "should lock and unlock the file"() {
    given:
    def fileToLock = share.openFile("fileToLock", EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE), null, EnumSet.noneOf(SMB2ShareAccess.class), FILE_CREATE, null)

    when:
    fileToLock.requestLock().exclusiveLock(0, 10, true).send()

    then:
    noExceptionThrown()

    when:
    fileToLock.requestLock().unlock(0, 10).send()

    then:
    noExceptionThrown()

    cleanup:
    fileToLock.close()
    share.rm("fileToLock")
  }

  def "should fail requesting overlapping exclusive lock range"() {
    given:
    def fileToLock = share.openFile("fileToLock", EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE), null, EnumSet.noneOf(SMB2ShareAccess.class), FILE_CREATE, null)

    when:
    fileToLock.requestLock().exclusiveLock(0, 10, true).send()
    fileToLock.requestLock().exclusiveLock(5, 15, true).send()

    then:
    thrown(SMBApiException.class)

    when:
    fileToLock.requestLock().unlock(0, 10).send()
    fileToLock.requestLock().exclusiveLock(5, 15, true).send()

    then:
    noExceptionThrown()

    when:
    fileToLock.requestLock().unlock(5, 15).send()
    fileToLock.close()

    then:
    noExceptionThrown()

    cleanup:
    share.rm("fileToLock")
  }

  def "should append to the file"() {
    given:
    def file = share.openFile("appendfile", EnumSet.of(AccessMask.FILE_WRITE_DATA), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN_IF, null)
    def bytes = new byte[1024 * 1024]
    Random.newInstance().nextBytes(bytes)
    def istream = new ByteArrayInputStream(bytes)

    when:
    def ostream = file.getOutputStream(new LoggingProgressListener())
    try {
      byte[] buffer = new byte[4096]
      int len
      while ((len = istream.read(buffer)) != -1) {
        ostream.write(buffer, 0, len)
      }
    } finally {
      istream.close()
      ostream.close()
      file.close()
    }

    then:
    share.fileExists("appendfile")

    when:
    def appendfile = share.openFile("appendfile", EnumSet.of(AccessMask.FILE_WRITE_DATA), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN_IF, null)
    def bytes2 = new byte[1024 * 1024]
    Random.newInstance().nextBytes(bytes2)
    def istream2 = new ByteArrayInputStream(bytes2)
    ostream = appendfile.getOutputStream(new LoggingProgressListener(), true)
    try {
      byte[] buffer = new byte[4096]
      int len
      while ((len = istream2.read(buffer)) != -1) {
        ostream.write(buffer, 0, len)
      }
    } finally {
      istream2.close()
      ostream.close()
      appendfile.close()
    }

    then:
    share.fileExists("appendfile")

    when:
    def readBytes = new byte[2 * 1024 * 1024]
    def readFile = share.openFile("appendfile", EnumSet.of(AccessMask.FILE_READ_DATA), null, SMB2ShareAccess.ALL, FILE_OPEN, null)
    try {
      def remoteIs = readFile.getInputStream(new LoggingProgressListener())
      try {
        def offset = 0
        while (offset < readBytes.length) {
          def read = remoteIs.read(readBytes, offset, readBytes.length - offset)
          if (read > 0) {
            offset += read
          } else {
            break
          }
        }
      } finally {
        remoteIs.close()
      }
    } finally {
      readFile.close()
    }

    then:
    readBytes == [bytes, bytes2].flatten()

    cleanup:
    share.rm("appendfile")
  }

  def "should be able to copy files remotely"() {
    given:
    def src = share.openFile("srcFile", EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, FILE_OVERWRITE_IF, null)
    src.write(new ArrayByteChunkProvider("Hello World!".getBytes(StandardCharsets.UTF_8), 0))
    src.close()

    src = share.openFile("srcFile", EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, FILE_OPEN, null)
    def dst = share.openFile("dstFile", EnumSet.of(AccessMask.FILE_WRITE_DATA), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)

    when:
    src.remoteCopyTo(dst)

    then:
    share.fileExists("dstFile")
    def srcSize = src.getFileInformation(FileStandardInformation.class).endOfFile
    def dstSize = dst.getFileInformation(FileStandardInformation.class).endOfFile
    srcSize == dstSize

    cleanup:
    try {
      share.rm("srcFile")
    } catch (SMBApiException e) {
      // Ignored
    }

    try {
      share.rm("dstFile")
    } catch (SMBApiException e) {
      // Ignored
    }
  }

  def "should correctly detect file existence"() {
    given:
    share.mkdir("im_a_directory")
    def src = share.openFile("im_a_file", EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, FILE_OVERWRITE_IF, null)
    src.write(new ArrayByteChunkProvider("Hello World!".getBytes(StandardCharsets.UTF_8), 0))
    src.close()

    expect:
    share.fileExists("im_a_file")
    !share.fileExists("im_a_directory")
    !share.fileExists("i_do_not_exist")

    cleanup:
    share.rm("im_a_file")
    share.rmdir("im_a_directory", false)
  }

  @Unroll
  def "should not fail if #method response is DELETE_PENDING for file"() {
    given:
    def textFile = share.openFile("test.txt", EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, FILE_CREATE, null)
    textFile.write(new ArrayByteChunkProvider("Hello World!".getBytes(StandardCharsets.UTF_8), 0))
    textFile.close()
    textFile = share.openFile("test.txt", EnumSet.of(AccessMask.GENERIC_ALL), null, SMB2ShareAccess.ALL, FILE_OPEN, null)
    textFile.deleteOnClose()

    when:
    func(share)

    then:
    noExceptionThrown()

    where:
    method       | func
    "rm"         | { s -> s.rm("test.txt") }
    "fileExists" | { s -> s.fileExists("test.txt") }
  }

  def "should write async file"() {
    given:
    def size = 2 * 1024 * 1024 + 10
    def file = share.openFile("bigfile", EnumSet.of(AccessMask.FILE_WRITE_DATA), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
    def bytes = new byte[size]
    Random.newInstance().nextBytes(bytes)
    def istream = new ByteArrayInputStream(bytes)

    when:
    def writtenFuture = file.writeAsync(new InputStreamByteChunkProvider(istream))
    file.close()
    istream.close()

    then:
    writtenFuture.get() == size

    when:
    def readBytes = new byte[size]
    def readFile = share.openFile("bigfile", EnumSet.of(AccessMask.FILE_READ_DATA), null, SMB2ShareAccess.ALL, FILE_OPEN, null)
    def read = 0
    for (;;) {
      def nrRead = readFile.read(readBytes, read, read, size - read)
      if (nrRead == -1) {
        break
      }
      read += nrRead
    }

    then:
    read == size
    readBytes == bytes
    cleanup:
    share.rm("bigfile")

  }

  def "should transfer file using GZIPOutputStream via InputStreamByteChunkProvider to SMB share"() {
    given:
//    def DATA_FILE = "dataFile.txt"
//    def DATA_FILE_ZIPPED = "dataFile.zip"
    def DATA_FILE = File.createTempFile("dataFile", "txt")
    def DATA_FILE_ZIPPED = File.createTempFile("dataFile", "zip")
    def SMB_FILE = "SMBFile.txt"
    def SMB_FILE_ZIP = "SMBFile.zip"
    def SMB_FILE_UNZIPPED = "SMBFileUnzipped.txt"

    def dst = share.openFile(SMB_FILE, EnumSet.of(AccessMask.FILE_WRITE_DATA), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
    def dstZipped = share.openFile(SMB_FILE_ZIP, EnumSet.of(AccessMask.FILE_WRITE_DATA), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
    def dstUnzipped = share.openFile(SMB_FILE_UNZIPPED, EnumSet.of(AccessMask.FILE_WRITE_DATA), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)

    //Generate data which can be compressed
    def localDataFile = new FileWriter(DATA_FILE)
    try {
      for (i in 0..10_000) {
        localDataFile.append("HelloWorld")
      }
    } finally {
      localDataFile.close()
    }

    //Compress data file locally
    def fis = new FileInputStream(DATA_FILE)
    def fos = new FileOutputStream(DATA_FILE_ZIPPED)
    def gzipOS = new GZIPOutputStream(fos)
    try {
      byte[] buffer = new byte[1024]
      int len;
      while ((len = fis.read(buffer)) != -1) {
        gzipOS.write(buffer, 0, len)
      }
    } finally {
      gzipOS.close()
      fos.close()
      fis.close()
    }

    when:
    //Write non-compressed file to SMB
    dst.write(new InputStreamByteChunkProvider(new FileInputStream(DATA_FILE)))
    //Write zipped file to SMB
    dstZipped.write(new InputStreamByteChunkProvider(new FileInputStream(DATA_FILE_ZIPPED)))
    //Unzip file using GZIPInputStream on the fly and write to SMB
    dstUnzipped.write(new InputStreamByteChunkProvider(new GZIPInputStream(new FileInputStream(DATA_FILE_ZIPPED))))

    then:
    share.fileExists(SMB_FILE)
    share.fileExists(SMB_FILE_ZIP)
    share.fileExists(SMB_FILE_UNZIPPED)

    def dstSize = dst.getFileInformation(FileStandardInformation.class).endOfFile
    def dstUnzippedSize = dstUnzipped.getFileInformation(FileStandardInformation.class).endOfFile

    //Neither size nor contents of file written to SMB from GZipInputStream match.
    // SMB_FILE_UNZIPPED file arrives filled with '0x0' on SMB share
    dstSize == dstUnzippedSize

    cleanup:
    share.rm(SMB_FILE)
    share.rm(SMB_FILE_ZIP)
    share.rm(SMB_FILE_UNZIPPED)
    DATA_FILE.delete()
    DATA_FILE_ZIPPED.delete()
  }
}
