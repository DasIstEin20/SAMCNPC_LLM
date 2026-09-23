package io.samcnpc.llm.expression

import io.samcnpc.llm.decision.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID
import kotlin.random.Random

@Timeout(20)
class SamExpressionAdversarialTest {
    private val id = UUID(3, 4)
    private fun rejected(text: String) = assertTrue(SamExpressionDecoder.decode(text, id, false) is DecisionDecodeResult.Rejected, text.take(160))

    @Test fun executableLookingInputsNeverBecomeAnAcceptedDecision() {
        val bad = listOf(
            "import os", "exec('x')", "eval('1')", "__import__('os').system('shutdown')",
            "subprocess('cmd')", "os.system('cmd')", "assign(__class__='X')", "assign(getattr(x,'y'))",
            "x=continue_task()", "continue_task();cancel()", "continue_task() cancel()", "continue_task()\ncontinue_task()",
            "if True: cancel()", "for x in []: cancel()", "while True: cancel()", "lambda: cancel()",
            "class X: pass", "def f(): pass", "[cancel() for x in []]", "ask_user(f'{x}')",
            "ask_user('x'+'y')", "ask_user('x' 'y')", "ask_user('x'*1000)", "ask_user(str(32))",
            "continue_task()[0]", "continue_task.__call__()", "continue_task()#comment", "#x\ncontinue_task()",
            "```python\ncontinue_task()\n```", "Here is my answer: continue_task()", "ask_υser('x')",
            "ask_user('x')\u0000", "ask_user(r'x')", "ask_user(b'x')", "ask_user('\\x41')",
            "ask_user('\\ud800')", "ask_user('\\udc00')", "ask_user('\ud800')", "ask_user('raw\nline')",
            "wait(trigger='DEADLINE',ticks=1e2)", "wait(trigger='DEADLINE',ticks=+40)",
            "wait(trigger='DEADLINE',ticks=040)", "wait(trigger='DEADLINE',ticks=0x20)",
            "wait(trigger='DEADLINE',ticks=NaN)", "wait(trigger='DEADLINE',ticks=Infinity)",
            "wait(trigger='DEADLINE',ticks=40.0)", "wait(trigger='DEADLINE',ticks=20+20)",
            "ask_user(question='a',question='b')", "ask_user(question='a','b')", "ask_user(*['x'])",
            "assign(navigate(destination=pos(0,64,0),shell='cmd'))", "assign(java.lang.Runtime())",
            "assign(navigate(dimension_id='minecraft:overworld',destination=pos(0,64,0),speed=999))",
            "assign(navigate(dimension_id='minecraft:overworld',destination=pos(0,64,0),command='/give'))",
            "assign(navigate(dimension_id='minecraft:overworld',destination=pos(0,64,0),speed='https://x'))",
            "continue_task(context_id='forged')", "ask_user('x',context_id='forged')", "assign()",
        )
        bad.forEach(::rejected)
        // Executable-looking question text is inert data, never a function invocation.
        val value = SamExpressionDecoder.decode("ask_user('exec shell https://example.invalid')", id, false)
        assertTrue(value is DecisionDecodeResult.Accepted)
        assertEquals(DecisionKind.ASK_USER, (value as DecisionDecodeResult.Accepted).value.kind)
    }

    @Test fun byteStringDepthListArgumentAndNumberLimitsRejectBeforeUnboundedWork() {
        rejected(" ".repeat(16385))
        rejected("ask_user('" + "漢".repeat(6000) + "')")
        rejected("ask_user('" + "x".repeat(257) + "')")
        assertDoesNotThrow { SamExpressionSyntax.parse("f('" + "x".repeat(1024) + "')") }
        assertThrows(IllegalArgumentException::class.java) { SamExpressionSyntax.parse("f('" + "x".repeat(1025) + "')") }
        assertDoesNotThrow { SamExpressionSyntax.parse("f(".repeat(16) + "0" + ")".repeat(16)) }
        assertThrows(IllegalArgumentException::class.java) { SamExpressionSyntax.parse("f(".repeat(17) + "0" + ")".repeat(17)) }
        assertDoesNotThrow { SamExpressionSyntax.parse("f([" + List(128) { "0" }.joinToString(",") + "])") }
        assertThrows(IllegalArgumentException::class.java) { SamExpressionSyntax.parse("f([" + List(129) { "0" }.joinToString(",") + "])") }
        assertDoesNotThrow { SamExpressionSyntax.parse("f(" + List(64) { "a$it=0" }.joinToString(",") + ")") }
        assertThrows(IllegalArgumentException::class.java) { SamExpressionSyntax.parse("f(" + List(65) { "a$it=0" }.joinToString(",") + ")") }
        for (number in listOf("9".repeat(10000), "30000001", "-30000001", "0." + "0".repeat(40)))
            assertThrows(IllegalArgumentException::class.java) { SamExpressionSyntax.parse("f($number)") }
        assertThrows(IllegalArgumentException::class.java) { SamExpressionSyntax.parse("a".repeat(65) + "()") }
        val tokens = assertThrows(IllegalArgumentException::class.java) { SamExpressionSyntax.parse("()".repeat(4097)) }
        assertEquals("EXPRESSION_TOKENS", tokens.message)
    }

    @Test fun deterministicMutationFuzzIsBoundedAndNeverThrowsOrChangesItsAnswer() {
        val random = Random(0x53414d)
        val seeds = listOf("continue_task()", "ask_user('Zażółć')", "wait(trigger='DEADLINE',ticks=40)",
            "assign(navigate(dimension_id='minecraft:overworld',destination=pos(-4.5,64,-2.5)))")
        val alphabet = "abcdefghijklmnopqrstuvwxyz_0123456789()[]=,.'\"-+;:#/\\ \n\t😀\ud800"
        repeat(5000) {
            val value = StringBuilder(seeds[random.nextInt(seeds.size)])
            repeat(random.nextInt(1, 12)) {
                when (random.nextInt(3)) {
                    0 -> value.insert(random.nextInt(value.length + 1), alphabet[random.nextInt(alphabet.length)])
                    1 -> if (value.isNotEmpty()) value.deleteCharAt(random.nextInt(value.length))
                    else -> if (value.isNotEmpty()) value.setCharAt(random.nextInt(value.length), alphabet[random.nextInt(alphabet.length)])
                }
            }
            val first = SamExpressionDecoder.decode(value.toString(), id, false)
            val second = SamExpressionDecoder.decode(value.toString(), id, false)
            assertEquals(first.javaClass, second.javaClass)
            if (first is DecisionDecodeResult.Rejected) assertEquals(first, second)
            else {
                first as DecisionDecodeResult.Accepted
                second as DecisionDecodeResult.Accepted
                assertEquals(id, first.value.contextId)
                assertEquals(first.value.kind, second.value.kind)
                assertTrue(first.value.kind in setOf(DecisionKind.CONTINUE, DecisionKind.ASK_USER, DecisionKind.WAIT, DecisionKind.ASSIGN))
                assertEquals(com.google.gson.Gson().toJsonTree(first.value), com.google.gson.Gson().toJsonTree(second.value))
            }
        }
    }
}
