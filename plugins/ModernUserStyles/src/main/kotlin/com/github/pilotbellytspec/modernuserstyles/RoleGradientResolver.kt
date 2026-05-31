package com.github.pilotbellytspec.modernuserstyles

import com.aliucord.wrappers.GuildRoleWrapper.Companion.color
import com.aliucord.wrappers.GuildRoleWrapper.Companion.position
import com.discord.api.role.GuildRole
import com.discord.models.member.GuildMember
import com.discord.stores.StoreStream

class RoleGradientResolver {
    private val runtimeRoleStyles = mutableMapOf<Long, RoleGradient>()
    private val runtimeRolePositions = mutableMapOf<Long, Int>()
    private val enhancedRoleColorsGuilds = mutableMapOf<Long, Boolean>()

    fun setGuildEnhancedRoleColors(guildId: Long, enabled: Boolean) {
        enhancedRoleColorsGuilds[guildId] = enabled
    }

    fun setRuntimeRoleGradient(roleId: Long, gradient: RoleGradient?) {
        if (gradient == null || gradient.primaryColor == 0) {
            runtimeRoleStyles.remove(roleId)
            runtimeRolePositions.remove(roleId)
            return
        }

        val normalized = gradient.copy(
            primaryColor = gradient.primaryColor and 0x00ffffff,
            secondaryColor = gradient.secondaryColor?.and(0x00ffffff),
            tertiaryColor = gradient.tertiaryColor?.and(0x00ffffff),
        )
        runtimeRoleStyles[roleId] = normalized
        normalized.position?.let { runtimeRolePositions[roleId] = it }
    }

    fun forMember(member: GuildMember?): RoleGradient? {
        if (member == null) return null

        val guildId = member.guildId
        val roleMap = StoreStream.getGuilds().roles[guildId].orEmpty()

        member.roles
            .mapNotNull { roleId ->
                val role = roleMap[roleId]
                val style = forRoleId(guildId, roleId, role) ?: return@mapNotNull null
                RoleStyleCandidate(
                    roleId = roleId,
                    position = rolePosition(roleId, role),
                    style = style,
                )
            }
            .sortedWith(compareByDescending<RoleStyleCandidate> { it.position }.thenByDescending { it.roleId })
            .firstOrNull()
            ?.let { return it.style }

        return null
    }

    private fun forRoleId(guildId: Long, roleId: Long, role: GuildRole?): RoleGradient? {
        runtimeRoleStyles[roleId]?.let {
            return if (guildAllowsEnhancedRoleColors(guildId)) {
                it
            } else {
                it.copy(secondaryColor = null, tertiaryColor = null)
            }
        }
        if (role == null) return null

        reflectedPrimaryColor(role)?.let { return it }
        return role.color.takeIf { it != 0 }?.let { RoleGradient(it and 0x00ffffff, position = role.position) }
    }

    private fun guildAllowsEnhancedRoleColors(guildId: Long): Boolean =
        enhancedRoleColorsGuilds[guildId] == true

    private fun reflectedPrimaryColor(role: GuildRole): RoleGradient? {
        role.javaClass.declaredFields.forEach { field ->
            runCatching {
                field.isAccessible = true
                val value = field[role] ?: return@forEach
                if (value.javaClass.name != "com.discord.api.role.GuildRoleColors") return@forEach

                val primary = value.readInt("primaryColor") ?: role.color
                if (primary == 0) return@forEach

                return RoleGradient(
                    primaryColor = primary and 0x00ffffff,
                    position = role.position,
                )
            }
        }
        return null
    }

    private fun rolePosition(roleId: Long, role: GuildRole?): Int =
        runtimeRolePositions[roleId] ?: runCatching { role?.position }.getOrNull() ?: 0

    private fun Any.readInt(name: String): Int? =
        runCatching {
            val field = javaClass.getDeclaredField(name).apply { isAccessible = true }
            when (val value = field[this]) {
                is Int -> value
                is Number -> value.toInt()
                else -> null
            }
        }.getOrNull()

    private data class RoleStyleCandidate(
        val roleId: Long,
        val position: Int,
        val style: RoleGradient,
    )
}
