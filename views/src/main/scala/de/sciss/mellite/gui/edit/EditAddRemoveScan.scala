/*
 *  EditAddRemoveScan.scala
 *  (Mellite)
 *
 *  Copyright (c) 2012-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.mellite
package gui
package edit

import javax.swing.undo.{UndoableEdit, AbstractUndoableEdit}

import de.sciss.lucre.stm
import de.sciss.synth.proc.Proc
import de.sciss.lucre.event.Sys

// direction: true = insert, false = remove
// XXX TODO - should disconnect links and restore them in undo
private[edit] final class EditAddRemoveScan[S <: Sys[S]](isAdd: Boolean,
                                                         procH: stm.Source[S#Tx, Proc.Obj[S]],
                                                         key: String)(implicit cursor: stm.Cursor[S])
  extends AbstractUndoableEdit {

  override def undo(): Unit = {
    super.undo()
    cursor.step { implicit tx => perform(isUndo = true) }
  }

  override def redo(): Unit = {
    super.redo()
    cursor.step { implicit tx => perform() }
  }

  override def die(): Unit = {
    val hasBeenDone = canUndo
    super.die()
    if (!hasBeenDone) {
      // XXX TODO: dispose()
    }
  }

  def perform()(implicit tx: S#Tx): Unit = perform(isUndo = false)

  private def perform(isUndo: Boolean)(implicit tx: S#Tx): Unit = {
    val scans   = procH().elem.peer.scans
    if (isAdd ^ isUndo)
      scans.add   (key)
    else
      scans.remove(key)
  }

  override def getPresentationName = s"${if (isAdd) "Add" else "Remove"} Scan"
}
object EditAddScan {
  def apply[S <: Sys[S]](proc: Proc.Obj[S], key: String)(implicit tx: S#Tx, cursor: stm.Cursor[S]): UndoableEdit = {
    val procH = tx.newHandle(proc)
    val res = new EditAddRemoveScan(isAdd = true, procH = procH, key = key)
    res.perform()
    res
  }
}

object EditRemoveScan {
  def apply[S <: Sys[S]](proc: Proc.Obj[S], key: String)(implicit tx: S#Tx, cursor: stm.Cursor[S]): UndoableEdit = {
    val procH = tx.newHandle(proc)
    val res = new EditAddRemoveScan(isAdd = false, procH = procH, key = key)
    res.perform()
    res
  }
}