package fi.kela.states

import net.corda.v5.base.annotations.CordaSerializable

@CordaSerializable
class Tag (
    val name: String,
    val value: String){

    override fun toString() : String{
        return "tagName: $name, tagValue: $value"
    }
}
