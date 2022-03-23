package net.minotsu.fitnesspractice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SampleTest {

    @Suppress("RemoveRedundantBackticks")
    @Test
    fun `サンプルテスト`() {
        assertThat<Int>(1).isEqualTo(1)
    }
}
