/*
 *  CodeView.scala
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

import javax.swing.undo.UndoableEdit

import de.sciss.desktop.UndoManager
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Sys, Disposable}
import de.sciss.mellite.gui.impl.interpreter.{CodeViewImpl => Impl}
import de.sciss.model.Model
import de.sciss.synth.proc.Code

import scala.concurrent.Future
import scala.swing.Action

object CodeView {
  trait Handler[S <: Sys[S], In, -Out] extends Disposable[S#Tx] {
    def in(): In
    def save(in: In, out: Out)(implicit tx: S#Tx): UndoableEdit
    def execute()(implicit tx: S#Tx): Unit // (in: In, out: Out)(implicit tx: S#Tx): Unit
  }

  /** If `graph` is given, the `apply` action is tied to updating the graph variable. */
  def apply[S <: Sys[S]](obj: Code.Obj[S], code0: Code, hasExecute: Boolean)
                        (handler: Option[Handler[S, code0.In, code0.Out]])
                        (implicit tx: S#Tx, workspace: Workspace[S], cursor: stm.Cursor[S],
                         compiler: Code.Compiler,
                         undoManager: UndoManager): CodeView[S] =
    Impl(obj, code0, hasExecute = hasExecute)(handler)

  sealed trait Update
  case class DirtyChange(value: Boolean) extends Update
}
trait CodeView[S <: Sys[S]] extends ViewHasWorkspace[S] with Model[CodeView.Update] {
  def isCompiling: Boolean

  def dirty: Boolean

  def save(): Future[Unit]

  // def updateSource(text: String)(implicit tx: S#Tx): Unit

  def undoAction: Action
  def redoAction: Action
}