import chocopy.ChocoPy;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import org.junit.Test;

import java.io.IOException;

public class AstTest {
    @Test
    public void test() throws ArgumentParserException, IOException {
        String[] args = new String[]{
                "--action", "ss", "--out", "prime.ast.json",
                "sample_programs/prime.py"
        };
        ChocoPy.main(
                args
        );
    }
}
