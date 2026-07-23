package com.smartreimburse.ui.navigation

import com.smartreimburse.data.AttachmentType

object Routes {
    const val HOME = "home"
    const val NEW_FORM = "expense-form/new"
    const val EDIT_FORM = "expense-form/{expenseId}"
    const val DETAIL = "expense-detail/{expenseId}"
    const val CAMERA = "camera/{type}/{runOcr}"
    const val SYNC = "sync"

    fun newForm(): String = NEW_FORM

    fun editForm(expenseId: Long): String = "expense-form/$expenseId"

    fun detail(expenseId: Long): String = "expense-detail/$expenseId"

    fun camera(type: AttachmentType, runOcr: Boolean): String = "camera/${type.name}/$runOcr"
}
