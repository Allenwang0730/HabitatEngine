package com.ailun.habitat.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** 只按目标写明的格式整形答案；目标没要求的格式不猜。 */
class AnswerFormatTest {

    private val count = "How many to-dos do I have in the 'Personal' folder? Express your answer as just a single number."
    private val todo = "Is the note titled 'Project Proposal' a todo item? Respond with either 'True' if it is a todo or 'False' if not."

    @Test
    fun numberGoalsGetTheBareNumber() {
        assertEquals("3", AnswerFormat.normalize(count, "3"))
        assertEquals("3", AnswerFormat.normalize(count, "The answer is 3."))
        assertEquals("3", AnswerFormat.normalize(count, "3 items"))
        assertEquals("1234", AnswerFormat.normalize(count, "1,234"))
        assertEquals("12", AnswerFormat.normalize(count, "答案是 12"))
        // 好几个数时不猜
        assertEquals("3 or 4", AnswerFormat.normalize(count, "3 or 4"))
    }

    @Test
    fun boolGoalsGetTrueOrFalse() {
        assertEquals("True", AnswerFormat.normalize(todo, "Yes"))
        assertEquals("False", AnswerFormat.normalize(todo, "false."))
        assertEquals("False", AnswerFormat.normalize(todo, "No, it is a regular note"))
    }

    @Test
    fun otherGoalsOnlyLoseQuotesAndFinalPeriod() {
        val g = "What is the title of the event on October 17 2023 at 20:00?"
        assertEquals("Quarterly review", AnswerFormat.normalize(g, "\"Quarterly review.\""))
        assertEquals("Workshop on Campaign, Movie night", AnswerFormat.normalize(g, "Workshop on Campaign, Movie night"))
        assertEquals("", AnswerFormat.normalize(g, "  "))
        // 不是数字题，数字原样保留
        assertEquals("3 items", AnswerFormat.normalize(g, "3 items"))
    }
}
