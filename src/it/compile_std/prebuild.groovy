File target = new File(basedir, "target/compile_std-0/ebin/plugin_compile.beam");
if (target.isFile()) {
    throw new IllegalStateException("The compiled target " + target + " must not exist before test is run.");
}

File mib = new File(basedir, "target/compile_std-0/priv/GOOD-MIB.bin");
if (mib.isFile()) {
  throw new IllegalStateException("The compiled target MIB file " + target + " must not exist before test is run.");
}
