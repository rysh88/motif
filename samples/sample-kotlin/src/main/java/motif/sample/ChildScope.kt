package motif.sample

import motif.Scope

@Scope
interface ChildScope {
    fun randomNumber(): Int

    fun greeter(): Greeter
}