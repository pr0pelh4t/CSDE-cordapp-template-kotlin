package fi.kela.states

import fi.kela.contracts.TokenContract
import net.corda.v5.base.annotations.CordaSerializable
import net.corda.v5.crypto.SecureHash
import net.corda.v5.ledger.utxo.BelongsToContract
import net.corda.v5.ledger.utxo.ContractState
import java.math.BigDecimal
import java.security.PublicKey

@CordaSerializable
@BelongsToContract(TokenContract::class)
class Token (
        private val participants: List<PublicKey>,
        val value: BigDecimal,
        val nominalValue: BigDecimal,
        val currency: String,
        val issuer: SecureHash,
        val symbol: String,
        val tag: String? = null,
        val ownerHash: SecureHash? = null,
    ) : ContractState {

    companion object {
        val tokenType = Token::class.java.name.toString()
    }

    /**
     * Helper method for transferring token ownership
     */
    fun transfer(newOwner: SecureHash, newParticipants: List<PublicKey>): Token {
        return Token(value = value, nominalValue = nominalValue, currency = currency, issuer = issuer, symbol = symbol,ownerHash = newOwner, participants = newParticipants)
    }

    /**
     * Helper method for changing value
     */
    fun add(addValue: BigDecimal): Token {
        return Token(value = value.add(addValue), nominalValue = nominalValue, currency = currency, issuer = issuer, symbol = symbol, ownerHash = ownerHash, participants = participants)
    }

    override fun getParticipants(): List<PublicKey>{
        return participants
    }

    override fun toString(): String {
        return "Symbol: $symbol, Owner: $ownerHash, value: $value, nominalValue: $nominalValue"
    }
}