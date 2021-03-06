/*
 *  WorkspaceImpl.scala
 *  (Mellite)
 *
 *  Copyright (c) 2012-2016 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.mellite
package impl

import java.io.{FileInputStream, FileNotFoundException, FileOutputStream, IOException}
import java.util.Properties

import de.sciss.file._
import de.sciss.lucre.stm.store.BerkeleyDB
import de.sciss.lucre.stm.{DataStore, Disposable, Obj, Sys, TxnLike}
import de.sciss.lucre.synth.{InMemory => InMem}
import de.sciss.lucre.{confluent, stm}
import de.sciss.serial.{DataInput, DataOutput, Serializer}
import de.sciss.synth.proc.SoundProcesses.atomic
import de.sciss.synth.proc.{Confluent, Durable => Dur, Folder}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.{Ref, Txn}
import scala.language.existentials

object WorkspaceImpl {
  private final class Ser[S <: Sys[S]] extends Serializer[S#Tx, S#Acc, Data[S]] {
    def write(data: Data[S], out: DataOutput): Unit =
      data.write(out)

    def read(in: DataInput, access: S#Acc)(implicit tx: S#Tx): Data[S] = {
      val cookie = in.readLong()
      if ((cookie & 0xFFFFFFFFFFFFFF00L) != COOKIE) sys.error(s"Unexpected cookie $cookie (should be $COOKIE)")
      val version = cookie.toInt & 0xFF
      if (version != VERSION) sys.error(s"Incompatible file version (found $version, but need $VERSION)")

      new Data[S] {
        val root = Folder.read[S](in, access)
      }
    }
  }

  private implicit val ConfluentSer: Ser[Cf]    = new Ser[Cf]
  private implicit def DurableSer  : Ser[Dur]   = ConfluentSer.asInstanceOf[Ser[Dur]]
  private implicit def InMemorySer : Ser[InMem] = ConfluentSer.asInstanceOf[Ser[InMem]]

  private def requireExists(dir: File): Unit =
    if (!dir.isDirectory) throw new FileNotFoundException(s"Workspace ${dir.path} does not exist")

  private def requireExistsNot(dir: File): Unit =
    if (dir.exists()) throw new IOException(s"Workspace ${dir.path} already exists")

  def read(dir: File, config: BerkeleyDB.Config): WorkspaceLike = {
    requireExists(dir)
    val fis   = new FileInputStream(dir / "open")
    val prop  = new Properties
    prop.load(fis)
    fis.close()
    val confluent = prop.getProperty("type") match {
      case "confluent"  => true
      case "ephemeral"  => false
      case other        => sys.error(s"Invalid property 'type': $other")
    }
    val res = if (confluent) readConfluent(dir, config) else readDurable(dir, config)
    res // .asInstanceOf[Workspace[~ forSome { type ~ <: SSys[~] }]]
  }

  private def setAllowCreate(in: BerkeleyDB.Config, value: Boolean): BerkeleyDB.Config =
    if (in.allowCreate == value) in else {
      val b = BerkeleyDB.ConfigBuilder(in)
      b.allowCreate = value
      b.build
    }

  def readConfluent(dir: File, config: BerkeleyDB.Config): Workspace.Confluent = {
    requireExists(dir)
    applyConfluent(dir, config = setAllowCreate(config, value = false))
  }

  def emptyConfluent(dir: File, config: BerkeleyDB.Config): Workspace.Confluent = {
    requireExistsNot(dir)
    applyConfluent(dir, config = setAllowCreate(config, value = true))
  }

  def readDurable(dir: File, config: BerkeleyDB.Config): Workspace.Durable = {
    requireExists(dir)
    applyDurable(dir, config = setAllowCreate(config, value = false))
  }

  def emptyDurable(dir: File, config: BerkeleyDB.Config): Workspace.Durable = {
    requireExistsNot(dir)
    applyDurable(dir, config = setAllowCreate(config, value = true))
  }

  def applyInMemory(): Workspace.InMemory = {
    type S    = InMem
    implicit val system: S = InMem()

    val access = system.root[Data[S]] { implicit tx =>
      val data: Data[S] = new Data[S] {
        val root = Folder[S](tx)
      }
      data
    }

    new InMemoryImpl(system, access)
  }

  private def openDataStore(dir: File, config: BerkeleyDB.Config, confluent: Boolean): DataStore.Factory = {
    val res             = BerkeleyDB.factory(dir, config)
    val fos             = new FileOutputStream(dir / "open")
    val prop            = new Properties()
    prop.setProperty("type", if (confluent) "confluent" else "ephemeral")
    prop.store(fos, "Mellite Workspace Meta-Info")
    fos.close()
    res
  }

  private def applyConfluent(dir: File, config: BerkeleyDB.Config): Workspace.Confluent = {
    type S    = Cf
    val fact  = openDataStore(dir, config = config, confluent = true)
    implicit val system: S = Confluent(fact)

    val (access, cursors) = system.rootWithDurable[Data[S], Cursors[S, S#D]] { implicit tx =>
      val data: Data[S] = new Data[S] {
        val root = Folder[S](tx)
      }
      data

    } { implicit tx =>
      val c   = Cursors[S, S#D](confluent.Access.root[S])
      c.name_=("master")
      c
    }

    new ConfluentImpl(dir, system, access, cursors)
  }

  private def applyDurable(dir: File, config: BerkeleyDB.Config): Workspace.Durable = {
    type S    = Dur
    val fact  = openDataStore(dir, config = config, confluent = false)
    implicit val system: S = Dur(fact)

    val access = system.root[Data[S]] { implicit tx =>
      val data: Data[S] = new Data[S] {
        val root = Folder[S](tx)
      }
      data
    }

    new DurableImpl(dir, system, access)
  }

  private final val COOKIE  = 0x4D656C6C69746500L  // "Mellite\0"
  private final val VERSION = 1

  private abstract class Data[S <: Sys[S]] {
    def root: Folder[S]

    final def write(out: DataOutput): Unit = {
      out.writeLong(COOKIE | VERSION)
      root.write(out)
    }

    final def dispose()(implicit tx: S#Tx): Unit =
      root.dispose()

    override def toString = s"Data ($root)"
  }

  private trait Impl[S <: Sys[S]] {
    _: Workspace[S] =>

    // ---- abstract ----

    protected def access: stm.Source[S#Tx, Data[S]]

    // ---- implemented ----

    override def toString = s"Workspace<${folder.fold("in-memory")(_.name)}>" // + hashCode().toHexString

    private val dependents  = Ref(Vec.empty[Disposable[S#Tx]])

    final val rootH: stm.Source[S#Tx, Folder[S]] = stm.Source.map(access)(_.root)

    final def root(implicit tx: S#Tx): Folder[S] = access().root

    final def addDependent   (dep: Disposable[S#Tx])(implicit tx: TxnLike): Unit =
      dependents.transform(_ :+ dep)(tx.peer)

    final def removeDependent(dep: Disposable[S#Tx])(implicit tx: TxnLike): Unit =
      dependents.transform { in =>
        val idx = in.indexOf(dep)
        require(idx >= 0, s"Dependent $dep was not registered")
        in.patch(idx, Nil, 1)
      } (tx.peer)

    final def collectObjects[A](pf: PartialFunction[Obj[S], A])(implicit tx: S#Tx): Vec[A] = {
      var b   = Vec.newBuilder[A]
      val fun = pf.lift

      def loop(f: Folder[S]): Unit =
        f.iterator.foreach { obj =>
          fun(obj).foreach(b += _)
          obj match {
            case ef: Folder[S] => loop(ef)
            case _ =>
          }
        }

      loop(root)
      b.result()
    }

    final def close(): Unit = {
      atomic[S, Unit] { implicit tx =>
        dispose()
      } (cursor)
    }

    final def dispose()(implicit tx: S#Tx): Unit = {
      // logInfoTx(s"Dispose workspace $name")

      // first dispose all dependents
      val dep = dependents.get(tx.peer)
      dep.foreach(_.dispose())
      dependents.update(Vec.empty)(tx.peer)

      // if the transaction is successful...
      Txn.afterCommit { _ =>
        // ...and close the database
        log(s"Closing system $system")
        system.close()
      } (tx.peer)
    }
  }

  private final class ConfluentImpl(_folder: File, val system: Cf, protected val access: stm.Source[Cf#Tx, Data[Cf]],
                                    val cursors: Cursors[Cf, Cf#D])
    extends Workspace.Confluent with Impl[Cf] {

    def folder  = Some(_folder)
    def name    = _folder.base

    val systemType = implicitly[reflect.runtime.universe.TypeTag[Cf]]

    type I = system.I
    val inMemoryBridge = (tx: S#Tx) => tx.inMemory  // Confluent.inMemory(tx)
    def inMemoryCursor: stm.Cursor[I] = system.inMemory

    // def cursor = cursors.cursor
    val cursor = confluent.Cursor.wrap(cursors.cursor)(system)
  }

  private final class DurableImpl(_folder: File, val system: Dur,
                                  protected val access: stm.Source[Dur#Tx, Data[Dur]])
    extends Workspace.Durable with Impl[Dur] {

    val systemType = implicitly[reflect.runtime.universe.TypeTag[Dur]]

    def folder  = Some(_folder)
    def name    = _folder.base

    type I = system.I
    val inMemoryBridge = (tx: S#Tx) => Dur.inMemory(tx)
    def inMemoryCursor: stm.Cursor[I] = system.inMemory

    def cursor: stm.Cursor[S] = system
  }

  private final class InMemoryImpl(val system: InMem, protected val access: stm.Source[InMem#Tx, Data[InMem]])
    extends Workspace.InMemory with Impl[InMem] {

    val systemType = implicitly[reflect.runtime.universe.TypeTag[InMem]]

    type I = system.I
    val inMemoryBridge = (tx: S#Tx) => tx
    def inMemoryCursor: stm.Cursor[I] = system.inMemory

    def cursor: stm.Cursor[S] = system

    def folder = None
    def name = "in-memory"
  }
}