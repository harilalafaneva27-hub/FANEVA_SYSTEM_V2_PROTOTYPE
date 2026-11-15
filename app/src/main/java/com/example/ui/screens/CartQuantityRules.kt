package com.example.ui.screens

/** UI guard for whole-unit sale quantities. Repository still validates stock authoritatively. */
internal object CartQuantityRules {
    fun isAllowed(requested: Int, available: Int): Boolean =
        requested > 0 && available >= 0 && requested <= available
}
