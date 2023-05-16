package fi.kela.states

import net.corda.v5.base.annotations.CordaSerializable
import java.math.BigDecimal

@CordaSerializable
class Balance (val symbol: String, val amount: BigDecimal)