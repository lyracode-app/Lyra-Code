# XML-defined formulas invoke constructors/methods and constants by name.
-keepclassmembers,allowoptimization class org.scilab.forge.jlatexmath.TeXFormula {
    public <init>(...);
    public *** *(...);
}
-keepclassmembers,allowoptimization class org.scilab.forge.jlatexmath.MacroInfo {
    public <init>(...);
}
-keepclassmembers,allowoptimization class org.scilab.forge.jlatexmath.TeXConstants {
    public static <fields>;
}
-keepclassmembers,allowoptimization class ru.noties.jlatexmath.awt.Color {
    public static <fields>;
}

# Alphabet registrations are loaded using Class.forName.
-keep,allowoptimization class org.scilab.forge.jlatexmath.cyrillic.CyrillicRegistration {
    public <init>();
}
-keep,allowoptimization class org.scilab.forge.jlatexmath.greek.GreekRegistration {
    public <init>();
}
