/*
 *  ViewHasWorkspace.scala
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

import de.sciss.lucre.stm.Sys
import de.sciss.lucre.swing.{View => SView}

trait ViewHasWorkspace[S <: Sys[S]] extends SView.Cursor[S] {
  implicit def workspace: Workspace[S]
}
