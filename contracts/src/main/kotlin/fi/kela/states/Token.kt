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
        return Token(value = value, currency = currency, issuer = issuer, symbol = symbol,ownerHash = newOwner, participants = newParticipants)
    }

    override fun getParticipants(): List<PublicKey>{
        return participants
    }
}