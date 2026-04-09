package motif.sample

import android.view.ViewGroup
import motif.Creatable
import motif.Scope

@Scope
interface ChildBuilder : Creatable<ChildBuilder.Dependencies> {
  
  // This matches the DisableUberNavBikeCouriersAgendaCardBuilder.build() pattern
  fun build(parentViewGroup: ViewGroup): ChildScope

  interface Dependencies {
//    fun viewGroup(): ViewGroup
  }
}
