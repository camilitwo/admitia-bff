package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.service.PrekinderFlowService.AlumniDeclaration;
import cl.mtn.admitiabff.prekinder.service.PrekinderFlowService.EligibilityDeclaration;

/**
 * Test-only wrapper to expose package-private static methods from PrekinderFlowService.
 */
public class PrekinderFlowServiceExtractAlumniYearTestWrapper {
    public static Integer extractAlumniParentYear(EligibilityDeclaration eligibility, int academicYear) {
        return PrekinderFlowService.extractAlumniParentYear(eligibility, academicYear);
    }
}
