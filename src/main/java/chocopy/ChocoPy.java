package chocopy;

import chocopy.codegen.ChocoPyCodeGen;
import chocopy.common.astnodes.CompilerError;
import chocopy.common.astnodes.Node;
import chocopy.common.astnodes.Program;
import chocopy.lexical.ChocoPyLexicalAnalysis;
import chocopy.semantic.ChocoPySemanticAnalysis;
import chocopy.venus.Venus;
import com.fasterxml.jackson.core.JsonProcessingException;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import java.io.*;
import java.util.List;
import java.util.Scanner;

import static net.sourceforge.argparse4j.impl.Arguments.storeTrue;

public class ChocoPy {

    /**
     * Compiler choices.
     * <p>
     * - s:     ChocoPy (.py)   --> Abstract Syntax Tree - AST (.ast)
     * - ss:    ChocoPy (.py)   --> Type Checked Abstract Syntax Tree - Typed AST (.ast.typed)
     * - sss:   ChocoPy (.py)   --> Assembly Code (.s)
     * - .s:    AST (.ast)      --> Typed AST (.ast.typed)
     * - .ss:   Ast (.ast)      --> Assembly Code (.s)
     * - ..s:   Typed AST (.ast.typed) --> Assembly Code (.s)
     */
    private static final String[] compileChoices =
            new String[]{"s", "ss", "sss", ".s", ".ss", "..s"};
    /**
     * Print debug information, if True.
     */
    private final boolean debug;
    /**
     * Name of source file.
     */
    private final String inputFileName;
    /**
     * Saved output destination.
     */
    private final File outFile;
    /**
     * Run generated assembly code, if True.
     */
    private final boolean runFlag;
    /**
     * Action options for passes.
     */
    private final char[] passes;
    /**
     * Contents of input file.
     */
    private final String input;
    /**
     * Upon compilation error, exit driver with error.
     */
    private boolean hasCompilerError = false;

    private final String CODE_EXT = ".py";
    private final String AST_EXT = ".ast";
    private final String TYPED_EXT = ".typed";
    private final String ASSEMBLY_EXT = ".s";
    private final String OUTPUT_EXT = ".result";
    /**
     * Program AST tree.
     */
    private Program program = null;

    public ChocoPy(boolean debug, String inputFileName, File outFile, boolean runFlag, char[] passes) throws IOException {
        this.debug = debug;
        this.inputFileName = inputFileName;
        this.outFile = outFile;
        this.runFlag = runFlag;
        this.passes = passes;
        this.input = this.readFile(inputFileName);
    }

    public ChocoPy(boolean debug, String inputFileName, char[] passes) throws IOException {
        this.debug = debug;
        this.inputFileName = inputFileName;
        this.outFile = null;
        this.runFlag = false;
        this.passes = passes;
        this.input = this.readFile(inputFileName);
    }

    /**
     * Main compiler driver entry point.
     */
    public static void main(String[] args) throws ArgumentParserException, IOException {
        ChocoPy compiler = parseArguments(args);
        compiler.compile();
    }

    /**
     * Parse command line argument.
     */
    private static ChocoPy parseArguments(String[] args) throws ArgumentParserException, IOException {
        ArgumentParser parser = ArgumentParsers.newFor("ChocoPy Compiler")
                .build()
                .description("ChocoPy Main Driver");

        /* POSITIONAL ARGUMENT */
        parser.addArgument("source").type(String.class)
                .help("Files to be compiled.");

        /* OPTIONAL ARGUMENT */
        parser.addArgument("--action").type(String.class)
                .choices(compileChoices).setDefault("")
                .help("Passes to run: `.` = skip pass, `s` = run pass");
        parser.addArgument("--run").action(storeTrue())
                .help("Execute generated assembly (requires code generation)");
        parser.addArgument("--execute").action(storeTrue())
                .help("Execute a ChocoPy program.\n" +
                        "This command ignores --action and --run flag and " +
                        "use all three passes (program -> lexical -> static analysis -> codegen -> run)");
        parser.addArgument("--debug").action(storeTrue())
                .help("Print debugging information.");
        parser.addArgument("--out").type(String.class)
                .help("Save output of compiler to FILE");

        char[] passes = new char[3];
        // parse argument
        Namespace res = parser.parseArgs(args);
        // source files
        String sourceFileName = res.get("source");
        // action passes
        String action = res.get("action") + "...";
        for (int i = 0; i < 3; ++i) {
            passes[i] = action.charAt(i);
        }
        // run flag
        boolean runFlag = res.get("run");
        // execute flag
        boolean executeFlag = res.get("execute");
        if (executeFlag) {
            passes[0] = 's';
            passes[1] = 's';
            passes[2] = 's';
            runFlag = true;
        }
        // debug flag
        boolean debug = res.get("debug");
        // initialize output file
        String outName = res.getString("out");
        File outFile = outName == null ? null : new File(outName);
        return new ChocoPy(debug, sourceFileName, outFile, runFlag, passes);
    }

    /**
     * Compile program, with command line ARGS.
     */
    public void compile() {

        if (this.passes[0] == '.' && this.passes[1] == '.' &&
                this.passes[2] == '.' && !this.runFlag) {
            System.err.println("You must specify --action or --run");
            System.exit(1);
        }

        if (this.inputFileName == null) {
            System.err.println("You must specify something to compile.");
            System.exit(1);
        }

        this.process();

        if (this.hasCompilerError) {
            System.exit(1);
        }
    }

    /**
     * Process file.
     */
    private void process() {
        System.out.println("Reading " + inputFileName);
        try {
            this.parse();
            this.analyze();
            String code = this.generate();
            String result = this.run(code);
            this.pipeResult(inputFileName, result);
        } catch (RuntimeException e) {
            System.err.printf("Unexpected exception: %s.", e);
            e.printStackTrace();
        } catch (JsonProcessingException e) {
            System.err.println("Input not a valid Program JSON");
        } catch (FileNotFoundException e) {
            System.err.println("File not found: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Unexpected I/O exception!");
            e.printStackTrace();
        }
    }

    /**
     * Pass 1: parse program.
     * <p>
     * If pass is not skipped, the pass performs the following action:
     * - source: ChocoPy (.py)
     * - dest:   Abstract Syntax Tree - AST (.ast)
     */
    private void parse() throws IOException {
        if (this.passes[0] != '.') {
            if (this.input == null) {
                this.program = null;
                return;
            }
            this.program = ChocoPyLexicalAnalysis.process(this.input, this.debug);
        } else if (this.passes[1] != '.' || this.passes[2] != '.') {
            this.program = Node.fromJSON(this.input, Program.class);
        }
    }

    /**
     * Pass 2: static analysis
     * <p>
     * If pass is not skipped, the pass performs the following action:
     * - source: Abstract Syntax Tree - AST (.ast)
     * - dest:   Type Checked Abstract Syntax Tree - Typed AST (.ast.typed)
     */
    private void analyze() {
        if (this.program != null && !this.program.hasErrors()) {
            if (this.passes[1] != '.') {
                this.program = ChocoPySemanticAnalysis.process(this.program, this.debug);
            }
        }
    }

    /**
     * Pass 3: code generation
     * <p>
     * If pass is not skipped, the pass performs the following action:
     * - source: Type Checked Abstract Syntax Tree - Typed AST (.ast.typed)
     * - dest:   modified  RISC-V assembly code (.s)
     */
    private String generate() {
        if (this.passes[2] == '.') {
            if (this.passes[0] == '.' && this.passes[1] == '.') {
                return this.input;
            } else if (this.program == null) {
                return "";
            }
            return this.program.toString();
        } else if (this.program == null || this.program.hasErrors()) {
            return "";
        }
        return ChocoPyCodeGen.process(this.program, this.debug);
    }

    /**
     * Run RISC-V assembly code.
     */
    private String run(String asmInput) {
        if (this.runFlag && (this.program == null || !this.program.hasErrors())) {
            return this.run(asmInput, true);
        }
        return asmInput;
    }

    /*
     * Run assembly code using simulator.
     * WARNING - Removed try catching itself - possible behavior change.
     */
    private String run(String asmInput, boolean capture) {
        ByteArrayOutputStream buffer;
        buffer = null;
        PrintStream savedOut = System.out;
        try {
            if (capture) {
                buffer = new ByteArrayOutputStream();
                PrintStream out = new PrintStream(buffer, true);
                System.setOut(out);
            }
            Venus.assembleLinkAndRun(asmInput);
        } finally {
            System.setOut(savedOut);
        }
        return capture ? buffer.toString() : null;
    }

    /**
     * Write compiler execution output.
     */
    private void pipeResult(String inputFileName, String result) throws IOException {
        // print any errors
        if (this.program != null) {
            this.printErrors(inputFileName, this.program.getErrorList());
        }
        // print result
        if (this.outFile != null) {
            this.writeFile(this.outFile, result);
        } else if (this.program == null || !this.program.hasErrors()) {
            System.out.println(result);
        }
    }

    /**
     * Read and return content of a INPUTFILENAME file.
     */
    private String readFile(String inputFileName) throws IOException {
        Scanner s = new Scanner(new File(inputFileName)).useDelimiter("\\Z");
        String str = s.hasNext() ? s.next() + "\n" : "";
        s.close();
        return str;
    }

    /**
     * Write output of TEXT to OUTFILE.
     */
    private void writeFile(File outFile, String text) throws FileNotFoundException {
        try (PrintWriter out = new PrintWriter(outFile)) {
            out.print(text);
            System.err.printf("Created and saved output in %s.%n", outFile);
        }
    }

    public boolean isDebug() {
        return debug;
    }

    public String getInputFileName() {
        return inputFileName;
    }

    public File getOutFile() {
        return outFile;
    }

    public boolean isRunFlag() {
        return runFlag;
    }

    public char[] getPasses() {
        return passes;
    }

    public Program getProgram() {
        return program;
    }

    public void setProgram(Program program) {
        this.program = program;
    }

    public String getInput() {
        return input;
    }

    /**
     * Print compiler errors and exit with error code 1.
     */
    private void printErrors(String srcName, List<CompilerError> errors) {
        for (CompilerError err : errors) {
            int[] loc = err.getLocation();
            System.err.printf("%s:%d:%d: %s%n", srcName, loc[0], loc[1], err.message);
            this.hasCompilerError = true;
        }
    }

}