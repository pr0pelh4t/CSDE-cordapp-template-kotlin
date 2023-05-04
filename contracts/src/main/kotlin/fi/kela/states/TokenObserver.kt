package fi.kela.states

import net.corda.v5.ledger.utxo.observer.UtxoLedgerTokenStateObserver
import net.corda.v5.ledger.utxo.observer.UtxoToken
import net.corda.v5.ledger.utxo.observer.UtxoTokenFilterFields
import net.corda.v5.ledger.utxo.observer.UtxoTokenPoolKey

class TokenObserver : UtxoLedgerTokenStateObserver<Token> {
    private val stateType = Token::class.java

    override fun onCommit(state: Token): UtxoToken {
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