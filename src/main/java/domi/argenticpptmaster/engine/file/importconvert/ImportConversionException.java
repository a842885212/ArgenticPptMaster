package domi.argenticpptmaster.engine.file.importconvert;

/** Fail-closed conversion error for Java import-sources converters. */
public final class ImportConversionException extends Exception {

    public ImportConversionException(String message) {
        super(message);
    }

    public ImportConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
