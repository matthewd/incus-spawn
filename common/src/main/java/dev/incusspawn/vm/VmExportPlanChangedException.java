package dev.incusspawn.vm;

/** The selected pool's running VM was launched with different host exports than now configured. */
public class VmExportPlanChangedException extends VmException {
    public VmExportPlanChangedException(String message) {
        super(message);
    }
}
