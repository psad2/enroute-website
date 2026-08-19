package com.enroute.auth

enum class Role {
    USER,
    MODERATOR,
    ADMIN;

    companion object {
        fun fromString(value: String?): Role {
            return when (value?.lowercase()) {
                "admin" -> ADMIN
                "moderator" -> MODERATOR
                "user" -> USER
                else -> USER
            }
        }
    }
}

fun hasRole(
    user: AuthenticatedUser,
    vararg allowedRoles: Role
): Boolean {
    val userRole = Role.fromString(user.role)

    return allowedRoles.contains(userRole)
}

// An admin passes this check too. Admin includes every moderator right.
fun isModerator(user: AuthenticatedUser): Boolean {
    return hasRole(
        user,
        Role.ADMIN,
        Role.MODERATOR
    )
}

fun isAdmin(user: AuthenticatedUser): Boolean {
    return hasRole(
        user,
        Role.ADMIN
    )
}