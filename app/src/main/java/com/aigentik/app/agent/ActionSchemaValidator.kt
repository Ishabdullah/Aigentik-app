package com.aigentik.app.agent

import com.aigentik.app.ai.AgentLLMFacade

/**
 * Converts a raw [AgentLLMFacade.CommandResult] into a typed [ActionSchema].
 *
 * This is the boundary between the LLM's free-form text output and the typed
 * safety layer. Any action that can't be mapped to a known schema becomes
 * [ActionSchema.Unknown], which the policy engine blocks by default.
 */
object ActionSchemaValidator {

    /**
     * Map a CommandResult (from AgentLLMFacade.interpretCommand) to a typed ActionSchema.
     * Returns [ActionSchema.Unknown] if the action string is unrecognised or required
     * fields are missing.
     */
    fun validate(result: AgentLLMFacade.CommandResult): ActionSchema = when (result.action) {

        "status", "check_status" ->
            ActionSchema.StatusQuery(verbose = result.query?.contains("verbose") == true)

        "find_contact", "contact_lookup" -> {
            val query = result.target?.trim()
            if (query.isNullOrBlank()) ActionSchema.Unknown(result.action)
            else ActionSchema.FindContact(query)
        }

        "get_contact_phone", "contact_phone", "phone_number" -> {
            val query = result.target?.trim()
            if (query.isNullOrBlank()) ActionSchema.Unknown(result.action)
            else ActionSchema.FindContact(query)
        }

        "check_email", "read_email", "list_email" ->
            ActionSchema.CheckEmail()

        "sync_contacts" ->
            ActionSchema.SyncContacts()

        "never_reply_to" -> {
            val target = result.target?.trim()
            if (target.isNullOrBlank()) ActionSchema.Unknown(result.action)
            else ActionSchema.SetContactInstructions(target, null, "never")
        }

        "always_reply_to" -> {
            val target = result.target?.trim()
            if (target.isNullOrBlank()) ActionSchema.Unknown(result.action)
            else ActionSchema.SetContactInstructions(target, null, "always")
        }

        "set_contact_instructions" -> {
            val target = result.target?.trim()
            val instructions = result.content?.trim()
            if (target.isNullOrBlank()) ActionSchema.Unknown(result.action)
            else ActionSchema.SetContactInstructions(target, instructions, null)
        }

        "send_email" -> {
            val to = result.target?.trim()
            val body = result.content?.trim()
            if (to.isNullOrBlank() || body.isNullOrBlank()) ActionSchema.Unknown(result.action)
            else ActionSchema.SendEmail(to, subject = "Message from Aigentik", body = body)
        }

        // send_sms: intentionally not implemented as an outbound action (MessageEngine
        // explicitly tells the admin this capability doesn't exist). Map to Unknown so
        // the policy engine blocks it cleanly with a reason rather than silently ignoring.
        "send_sms" ->
            ActionSchema.Unknown("send_sms (outbound SMS not supported — reply-only)")

        else -> ActionSchema.Unknown(result.action)
    }

    /**
     * Build an [ActionSchema.AutoReply] for a public inbound message.
     * Used by handlePublicMessage to represent the auto-reply action before it fires.
     */
    fun autoReply(toPhone: String, channel: String, draft: String): ActionSchema.AutoReply =
        ActionSchema.AutoReply(toPhone = toPhone, channel = channel, draft = draft)
}
