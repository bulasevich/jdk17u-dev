/*
 * Copyright (c) 2026, BELLSOFT. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package compiler.uncommontrap;

import java.util.List;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

/*
 * @test
 * @bug 8378719
 * @requires vm.debug == true & vm.compiler2.enabled
 * @summary Reproduces the null_check action=none deoptimization storm.
 *          In production the storm is triggered when a JVMTI agent (APM) enables exception
 *          events: C2 emits uncommon_trap(null_check, Action_none) via
 *          uncommon_trap_if_should_post_on_exceptions. This test exercises the same
 *          uncommon_trap_inner path via builtin_throw_too_many_traps, which fires when:
 *          (1) the method has an exception handler (has_exception_handler=true, as in the
 *              production method), making is_builtin_throw_hot true after just one null trap,
 *          (2) a prior maybe_recompile has set trap_recompiled_at, and
 *          (3) OmitStackTraceInFastThrow=false forces ex_obj=null so the fast inline-throw
 *              path is skipped. The nmethod stays valid (Action_none) and every subsequent
 *              null hit deoptimizes from the same compiled frame — a null_check deopt storm.
 * @library /test/lib
 *
 * @run main/othervm compiler.uncommontrap.DeoptStormNullCheck
 */
public class DeoptStormNullCheck {

    private static final int MAX_DEOPT_COUNT = 100;

    public static void main(String[] args) throws Exception {
        if (args.length >= 1) {
            new DeoptStormNullCheck().run();
            return;
        }

        String className = DeoptStormNullCheck.class.getName();
        String[] procArgs = {
            "-XX:PerMethodRecompilationCutoff=2",
            // OmitStackTraceInFastThrow=false: forces ex_obj=null in builtin_throw,
            // bypassing the fast inline-throw path and reaching builtin_throw_too_many_traps
            // which emits uncommon_trap(null_check, Action_none) — the same trap fired
            // in production via the JVMTI exception-event path.
            "-XX:-OmitStackTraceInFastThrow",
            "-XX:CompileCommand=dontinline,compiler.uncommontrap.DeoptStormNullCheck::*",
            "-XX:+TraceDeoptimization",
            className, "dummy"};
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(procArgs);
        OutputAnalyzer out = new OutputAnalyzer(pb.start());
        List<String> lines = out.asLines();
        long deoptCount = lines.stream()
            .filter(l -> l.contains("Uncommon trap") && l.contains("deoptStorm") && l.contains("null_check"))
            .count();
        if (deoptCount > MAX_DEOPT_COUNT) {
            System.out.println(out.getStdout());
            throw new RuntimeException("Failed: too many null_check deoptimizations in deoptStorm: "
                + deoptCount + " > " + MAX_DEOPT_COUNT);
        }
        out.shouldHaveExitValue(0);
    }

    private static int iteration;
    private static Object[] refs;
    private static int max_index;

    public void run() {
        max_index = 999;
        refs = new Object[max_index + 1];
        for (int i = 0; i < max_index; i++) refs[i] = new Object();
        refs[max_index] = null;
        iteration = 0;
        for (int i = 0; i < 100_000_000; i++) {
            deoptStorm();
        }
    }

    // The try-catch here mirrors the production method structure (catch(Exception) at top
    // level). With has_exception_handler=true, is_builtin_throw_hot fires after just one
    // null_check trap. Combined with OmitStackTraceInFastThrow=false, C2 emits
    // uncommon_trap(null_check, Action_none): the nmethod stays valid and every null hit
    // deoptimizes from the same compiled frame repeatedly.
    public int deoptStorm() {
        iteration = (iteration < max_index) ? iteration + 1 : 0;
        try {
            return refs[iteration].hashCode();
        } catch (NullPointerException e) {
            return -1;
        }
    }
}
