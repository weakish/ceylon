package foo.foo;

import com.redhat.ceylon.compiler.java.metadata.Module;
import com.redhat.ceylon.compiler.java.metadata.Import;

@Module(name = "foo.foo",
        version = "1", 
        dependencies = {
        @Import(name = "a.a", version = "1"),
        @Import(name = "b.b", version = "2", export = true),
        @Import(name = "c.c", version = "3", optional = true),
})
public class $module_ {
}
