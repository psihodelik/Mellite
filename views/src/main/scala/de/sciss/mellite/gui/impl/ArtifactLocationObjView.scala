/*
 *  ArtifactLocationObjView.scala
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
package gui
package impl

import javax.swing.undo.UndoableEdit

import de.sciss.desktop
import de.sciss.file._
import de.sciss.icons.raphael
import de.sciss.lucre.artifact.ArtifactLocation
import de.sciss.lucre.stm
import de.sciss.lucre.stm.Obj
import de.sciss.lucre.swing._
import de.sciss.lucre.synth.Sys
import de.sciss.mellite.gui.edit.EditArtifactLocation
import de.sciss.synth.proc.Implicits._

object ArtifactLocationObjView extends ListObjView.Factory {
  type E[~ <: stm.Sys[~]] = ArtifactLocation[~] // Elem[S]
  val icon      = ObjViewImpl.raphaelIcon(raphael.Shapes.Location)
  val prefix    = "ArtifactLocation"
  def humanName = "File Location"
  def tpe    = ArtifactLocation
  def hasMakeDialog = true

  def category = ObjView.categResources

  def mkListView[S <: Sys[S]](obj: ArtifactLocation[S])(implicit tx: S#Tx): ArtifactLocationObjView[S] with ListObjView[S] = {
    val peer      = obj
    val value     = peer.directory
    val editable  = ArtifactLocation.Var.unapply(peer).isDefined // .modifiableOption.isDefined
    new Impl(tx.newHandle(obj), value, isEditable = editable).init(obj)
  }

  type Config[S <: stm.Sys[S]] = ObjViewImpl.PrimitiveConfig[File]

  def initMakeDialog[S <: Sys[S]](workspace: Workspace[S], window: Option[desktop.Window])
                                 (implicit cursor: stm.Cursor[S]): Option[Config[S]] =
    ActionArtifactLocation.queryNew(window = window)

  def makeObj[S <: Sys[S]](config: (String, File))(implicit tx: S#Tx): List[Obj[S]] = {
    val (name, directory) = config
    val obj  = ArtifactLocation.newVar[S](directory)
    obj.name = name
    obj :: Nil
  }

  final class Impl[S <: Sys[S]](val objH: stm.Source[S#Tx, ArtifactLocation[S]],
                                var directory: File, val isEditable: Boolean)
    extends ArtifactLocationObjView[S]
    with ListObjView /* .ArtifactLocation */[S]
    with ObjViewImpl.Impl[S]
    with ListObjViewImpl.StringRenderer
    with ObjViewImpl.NonViewable[S] {

    override def obj(implicit tx: S#Tx) = objH()

    type E[~ <: stm.Sys[~]] = ArtifactLocation[~]

    def factory = ArtifactLocationObjView

    def value   = directory

    def init(obj: ArtifactLocation[S])(implicit tx: S#Tx): this.type = {
      initAttrs(obj)
      disposables ::= obj.changed.react { implicit tx => upd =>
        deferTx {
          directory = upd.now
        }
        fire(ObjView.Repaint(this))
      }
      this
    }

    def tryEdit(value: Any)(implicit tx: S#Tx, cursor: stm.Cursor[S]): Option[UndoableEdit] = {
      val dirOpt = value match {
        case s: String  => Some(file(s))
        case f: File    => Some(f)
        case _          => None
      }
      dirOpt.flatMap { newDir =>
        val loc = obj
        import de.sciss.equal.Implicits._
        if (loc.directory === newDir) None else ArtifactLocation.Var.unapply(loc).map { mod =>
          EditArtifactLocation(mod, newDir)
        }
      }
    }
  }
}
trait ArtifactLocationObjView[S <: stm.Sys[S]] extends ObjView[S] {
  override def obj(implicit tx: S#Tx): ArtifactLocation[S]

  def objH: stm.Source[S#Tx, ArtifactLocation[S]]

  def directory: File
}