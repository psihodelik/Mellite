/*
 *  ProcSelectionModel.scala
 *  (Mellite)
 *
 *  Copyright (c) 2012-2013 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss
package mellite
package gui

import de.sciss.synth.proc.Sys
import de.sciss.model.Model
import de.sciss.mellite.gui.impl.timeline.{ProcSelectionModelImpl, ProcView}

object ProcSelectionModel {
  def apply[S <: Sys[S]]: ProcSelectionModel[S] = new ProcSelectionModelImpl[S]

  type Listener[S <: Sys[S]] = Model.Listener[Update[S]]
  final case class Update[S <: Sys[S]](added: Set[ProcView[S]], removed: Set[ProcView[S]])
}
trait ProcSelectionModel[S <: Sys[S]] extends Model[ProcSelectionModel.Update[S]] {
  def contains(view: ProcView[S]): Boolean
  def +=(view: ProcView[S]): Unit
  def -=(view: ProcView[S]): Unit
  def clear(): Unit
  def iterator: Iterator[ProcView[S]]
}