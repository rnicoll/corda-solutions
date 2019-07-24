package com.r3.businessnetworks.membership.flows.bno

import co.paralleluniverse.fibers.Suspendable
import com.r3.businessnetworks.membership.flows.NotBNOException
import com.r3.businessnetworks.membership.flows.bno.service.BNOConfigurationService
import com.r3.businessnetworks.membership.flows.bno.support.BusinessNetworkOperatorFlowLogic
import com.r3.businessnetworks.membership.flows.member.OnBoardingRequest
import com.r3.businessnetworks.membership.flows.member.RequestMembershipFlow
import com.r3.businessnetworks.membership.flows.member.Utils.throwExceptionIfNotBNO
import com.r3.businessnetworks.membership.flows.member.service.MemberConfigurationService
import com.r3.businessnetworks.membership.states.MembershipContract
import com.r3.businessnetworks.membership.states.MembershipState
import com.r3.businessnetworks.membership.states.MembershipStatus
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

/**
 * Self Issue a Membership.  This can only be done by a BNO,
 * @param bnoMembership bno membership state to be activated
 */
@Suppress("UNUSED_VARIABLE")
@StartableByRPC
@InitiatingFlow(version = 2)
open class SelfIssueMembershipFlow(val bnoMembership: StateAndRef<MembershipState<Any>>) : FlowLogic<SignedTransaction>() {
    companion object {
        object ActivatedMembership : ProgressTracker.Step("Membership Activated")
        object ActivatingMembership : ProgressTracker.Step("Activating Membership")
        object AlreadyActvie : ProgressTracker.Step("Membership is already active")

        fun tracker() = ProgressTracker(
                ActivatedMembership,
                ActivatingMembership,
                AlreadyActvie
        )
    }

    override val progressTracker = tracker()

    @Suspendable

    //The call function will build a transaction which will only
    // modify the BNO status and then self sign the transaction
    override fun call(): SignedTransaction {
        // stop if Node is not a BNO
        throwExceptionIfNotBNO(ourIdentity, serviceHub)
        val configuration = serviceHub.cordaService(BNOConfigurationService::class.java)
        val notary = configuration.notaryParty()

        //txBuilderNoNotary is used to build a transaction with notary
        val txBuilder = TransactionBuilder(notary)
        val stx = serviceHub.signInitialTransaction(txBuilder)

        if (!bnoMembership.state.data.isActive()) {
            logger.info("Membership is being activated")
            progressTracker.currentStep = ActivatingMembership
            txBuilder.addInputState(bnoMembership)

            //transaction will only modify the status of the BNO node and set it to ACTIVE
            txBuilder.addOutputState(bnoMembership.state.data.copy(
                            status = MembershipStatus.ACTIVE,
                            modified = serviceHub.clock.instant()),
                            MembershipContract.CONTRACT_NAME)
            txBuilder.addCommand(MembershipContract.Commands.Activate(), ourIdentity.owningKey)

            // sign the transaction so it can be written to the ledger
            subFlow(FinalityFlow(stx, listOf())) //listOf remains empty since only BNO needed to sign the transaction
            logger.info("Membership has been activated")
            progressTracker.currentStep = ActivatedMembership
            return stx

        } else {
            progressTracker.currentStep = AlreadyActvie
            return stx
        }
    }
}

/**
 * This is a convenience flow that can be easily used from a command line
 *
 * @param party whose membership state to be activated
 */
@InitiatingFlow
@StartableByRPC
open class ActivateBnoMembershipFlow(val party: Party) : BusinessNetworkOperatorFlowLogic<SignedTransaction>() {

    companion object {
        object LOOKING_FOR_MEMBERSHIP_STATE : ProgressTracker.Step("Looking for party's membership state")
        object ACTIVATING_THE_MEMBERSHIP_STATE : ProgressTracker.Step("Activating the membership state")

        fun tracker() = ProgressTracker(
                LOOKING_FOR_MEMBERSHIP_STATE,
                ACTIVATING_THE_MEMBERSHIP_STATE
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        progressTracker.currentStep = LOOKING_FOR_MEMBERSHIP_STATE
        val stateToActivate = findMembershipStateForParty(party)

        progressTracker.currentStep = ACTIVATING_THE_MEMBERSHIP_STATE
        return subFlow(SelfIssueMembershipFlow(stateToActivate))
    }

}
