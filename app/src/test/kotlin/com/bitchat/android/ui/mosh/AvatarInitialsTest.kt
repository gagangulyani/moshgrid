package com.bitchat.android.ui.mosh

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarInitialsTest {
    @Test fun two_words_give_two_letters() = assertEquals("AM", avatarInitials("ana moshes"))
    @Test fun emoji_is_skipped_not_cut_in_half() = assertEquals("J", avatarInitials("Jo 🎧"))
    @Test fun leading_emoji_word_skipped() = assertEquals("KR", avatarInitials("🔥 kai rao"))
    @Test fun only_symbols_gives_question_mark() = assertEquals("?", avatarInitials("🎧 !!"))
    @Test fun empty_gives_question_mark() = assertEquals("?", avatarInitials("   "))
    @Test fun non_latin_letters_work() = assertEquals("अ", avatarInitials("अना"))
}
