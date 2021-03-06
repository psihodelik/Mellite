/*
 *  FolderFrame.scala
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

package de.sciss
package mellite
package gui

import de.sciss.lucre.stm
import de.sciss.lucre.swing.CellView
import de.sciss.lucre.synth.Sys
import de.sciss.mellite.gui.impl.document.{FolderFrameImpl => Impl}
import de.sciss.synth.proc.Folder

object FolderFrame {
  /** Creates a new frame for a folder view.
    *
    * @param workspace        the workspace whose root to display
    * @param name             optional window name
    * @param isWorkspaceRoot  if `true`, closes the workspace when the window closes; if `false` does nothing
    *                         upon closing the window
    */
  def apply[S <: Sys[S]](name: CellView[S#Tx, String], isWorkspaceRoot: Boolean)
                        (implicit tx: S#Tx, workspace: Workspace[S], cursor: stm.Cursor[S]): FolderFrame[S] =
    Impl(name = name, folder = workspace.rootH(), isWorkspaceRoot = isWorkspaceRoot)

  def apply[S <: Sys[S]](name: CellView[S#Tx, String], folder: Folder[S])
                        (implicit tx: S#Tx, workspace: Workspace[S], cursor: stm.Cursor[S]): FolderFrame[S] = {
    Impl(name = name, folder = folder, isWorkspaceRoot = false)
  }
}

trait FolderFrame[S <: Sys[S]] extends lucre.swing.Window[S] {
  def folderView: FolderView[S]
}