package com.rishi.aegis.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rishi.aegis.core.Breach
import com.rishi.aegis.ui.AegisError
import com.rishi.aegis.ui.AegisGreen
import com.rishi.aegis.ui.ErrorText
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.RunButton
import com.rishi.aegis.ui.SectionLabel
import com.rishi.aegis.ui.ToolPage
import kotlinx.coroutines.launch

object BreachScreens {

    @Composable
    fun BreachCheck(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var password by remember { mutableStateOf("") }
        var show by remember { mutableStateOf(false) }
        var running by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<Breach.Result?>(null) }

        ToolPage("Breach Check", onBack) {
            Note(
                "Checks Have I Been Pwned's database of billions of leaked passwords — without ever " +
                    "sending your password. Aegis SHA-1s it on-device and sends only the first 5 " +
                    "characters of the hash; the match is finished locally. This is k-anonymity."
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; result = null },
                label = { Text("Password to check") },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    Text(
                        if (show) "hide" else "show",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { show = !show }
                            .padding(horizontal = 12.dp),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            RunButton("Check", running, enabled = password.isNotEmpty()) {
                running = true; result = null
                scope.launch {
                    result = Breach.checkPassword(password)
                    running = false
                }
            }

            result?.let { r ->
                if (r.error != null) {
                    ErrorText(r.error)
                } else {
                    Verdict(r)
                    Spacer(Modifier.height(4.dp))
                    SectionLabel("What left your phone")
                    Note(
                        "Only the hash prefix \"${r.prefix}\" was sent. HIBP returned every leaked " +
                            "hash starting with it (padded with decoys), and Aegis matched the rest " +
                            "here. The server never learned your password."
                    )
                }
            }
        }
    }

    @Composable
    private fun Verdict(r: Breach.Result) {
        val color = if (r.pwned) AegisError else AegisGreen
        Surface(
            color = color.copy(alpha = 0.14f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (r.pwned) "Found in breaches" else "Not found",
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Spacer(Modifier.height(6.dp))
                if (r.pwned) {
                    Text(
                        "Seen ${"%,d".format(r.count)} times in known data breaches. Never use this " +
                            "password anywhere — attackers try leaked passwords first.",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                } else {
                    Text(
                        "This password isn't in HIBP's leaked set. That's good, but it doesn't prove " +
                            "the password is strong — only that it hasn't been seen in a known leak.",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}
