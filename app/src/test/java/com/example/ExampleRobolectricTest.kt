package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.extractJubelioToken
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("GudangSync", appName)
  }

  @Test
  fun `extractJubelioToken parses json nested data and raw jwt`() {
    assertEquals("abc123token", extractJubelioToken("""{"token":"Bearer abc123token"}"""))
    assertEquals("nested999", extractJubelioToken("""{"data":{"access_token":"nested999"}}"""))
    assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", extractJubelioToken("\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\""))
  }
}
