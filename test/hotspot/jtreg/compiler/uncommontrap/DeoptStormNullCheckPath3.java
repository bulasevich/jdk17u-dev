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
 * @summary Reproduces the null_check deoptimization storm via too_many_recompiles (PATH 3).
 *          Two distinct null-check BCIs in deoptStorm3 are required.  BCI_A fires once
 *          so that trap_count(null_check)!=0 and trap_recompiled_at(BCI_A)=true; after
 *          that an unstable_if deopt of deoptStorm3 brings decompile_count to m_cutoff.
 *          At the next (3rd) compile, BCI_B has never fired, so is_builtin_throw_hot=false
 *          while too_many_recompiles(null_check)=true — C2 emits Action_none for the
 *          BCI_B null_check via the uncommon_trap Action_reinterpret downgrade path.
 *          The nmethod stays valid and every subsequent null dereference
 *          at BCI_B deoptimizes from the same compiled frame — the storm.
 *          The fix prevents the Action_reinterpret -> Action_none downgrade when
 *          too_many_recompiles is true.
 * @requires vm.debug == true & vm.compiler2.enabled
 * @library /test/lib
 *
 * @run main/othervm compiler.uncommontrap.DeoptStormNullCheckPath3
 */
public class DeoptStormNullCheckPath3 {

    private static final int MAX_DEOPT_COUNT = 100;

    public static void main(String[] args) throws Exception {
        if (args.length >= 1) {
            new DeoptStormNullCheckPath3().run();
            return;
        }

        String className = DeoptStormNullCheckPath3.class.getName();
        String[] procArgs = {
            // m_cutoff = PerMethodRecompilationCutoff/2+1 = 2; two make_not_entrant
            // events on deoptStorm3 (one null_check, one unstable_if) bring
            // decompile_count to m_cutoff, triggering too_many_recompiles on the
            // 3rd compile for BCI_B which has never been a trap site.
            "-XX:PerMethodRecompilationCutoff=2",
            "-XX:-TieredCompilation",
            "-XX:CompileCommand=dontinline,compiler.uncommontrap.DeoptStormNullCheckPath3::*",
            "-XX:+TraceDeoptimization",
            className, "dummy"};
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(procArgs);
        OutputAnalyzer out = new OutputAnalyzer(pb.start());
        List<String> lines = out.asLines();
        long deoptCount = lines.stream()
            .filter(l -> l.contains("Uncommon trap") && l.contains("deoptStorm3") && l.contains("null_check"))
            .count();
        if (deoptCount > MAX_DEOPT_COUNT) {
            System.out.println(out.getStdout());
            throw new RuntimeException("Failed: too many null_check deoptimizations in deoptStorm3: "
                + deoptCount + " > " + MAX_DEOPT_COUNT);
        }
        out.shouldHaveExitValue(0);
    }

    // BCI_A null_check: fires once during setup; sets trap_recompiled_at(BCI_A)=true
    // and trap_count(null_check)!=0 in the MDO.
    static Object a = new Object();
    // BCI_B null_check: never fires until the storm phase; at compile time of the
    // 3rd nmethod, too_many_recompiles(null_check)=true while is_builtin_throw_hot=false
    // (trap_recompiled_at(BCI_B)=false, no exception handler) → PATH-3 storm without fix.
    static Object b = new Object();
    // Unstable branch to trigger the 2nd make_not_entrant of deoptStorm3.
    static boolean flip = false;

    public void run() {
        // Phase 0: warm up — compile deoptStorm3 with all non-null inputs
        for (int i = 0; i < 200_000; i++) {
            deoptStorm3();
        }
        // Phase 1a: fire BCI_A null_check once → make_not_entrant (decompile_count=1),
        //           trap_count(null_check)=1 in MDO.
        a = null;
        for (int i = 0; i < 10; i++) {
            try { deoptStorm3(); } catch (NullPointerException ignored) {}
        }
        a = new Object();
        // Allow the 2nd compile to complete; at this compile decompile_count=1 < m_cutoff=2
        // so BCI_B still gets Action_maybe_recompile (no storm yet).
        for (int i = 0; i < 100_000; i++) {
            deoptStorm3();
        }
        // Phase 1b: fire the unstable_if branch → make_not_entrant (decompile_count=2=m_cutoff).
        flip = true;
        for (int i = 0; i < 10; i++) {
            try { deoptStorm3(); } catch (NullPointerException ignored) {}
        }
        flip = false;
        // Allow the 3rd compile to complete.  At this compile decompile_count=2 >= m_cutoff=2
        // and trap_count(null_check)!=0 → too_many_recompiles(null_check)=true for BCI_B.
        // Without the fix: BCI_B gets Action_none; with the fix: downgrade is prevented.
        for (int i = 0; i < 100_000; i++) {
            deoptStorm3();
        }
        // Phase 2 (storm test): BCI_B fires for the first time.
        // Without fix: Action_none nmethod stays valid, every call deoptimizes — storm.
        // With fix: no deoptimization storm.
        b = null;
        for (int i = 0; i < 10_000; i++) {
            try { deoptStorm3(); } catch (NullPointerException ignored) {}
        }
        b = new Object();
    }

    // No exception handler: has_exception_handler()=false when compiled standalone
    // (dontinline).  This keeps is_builtin_throw_hot=false for BCI_B even after
    // BCI_A has been a trap site, so PATH-3 of builtin_throw() is reached and
    // too_many_recompiles(null_check) decides the action for the unseen BCI_B.
    public static int deoptStorm3() {
        // Unstable branch: profiled always-false; flip=true triggers unstable_if deopt.
        int r = flip ? 1 : 0;
        r += a.hashCode();  // BCI_A null_check
        r += b.hashCode();  // BCI_B null_check — storm target
        return r;
    }
}
