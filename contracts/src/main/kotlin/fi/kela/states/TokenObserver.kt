package fi.kela.states

import net.corda.v5.application.crypto.DigestService
import net.corda.v5.ledger.utxo.observer.UtxoLedgerTokenStateObserver
import net.corda.v5.ledger.utxo.observer.UtxoToken
import net.corda.v5.ledger.utxo.observer.UtxoTokenFilterFields
import net.corda.v5.ledger.utxo.observer.UtxoTokenPoolKey
import org.slf4j.LoggerFactory

class TokenObserver : UtxoLedgerTokenStateObserver<Token> {
    private val stateType = Token::class.java

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }


    override fun onCommit(state: Token, digestService: DigestService): UtxoToken {
        log.warn("state update $state")
        return UtxoToken(
                UtxoTokenPoolKey(
                    state.symbol,
                    state.issuer,
                    state.currency
                ),
                state.value,
                UtxoTokenFilterFields(state.tag, state.ownerHash)
        )
    }

    override fun getStateType(): Class<Token> {
        return this.stateType
    }
}