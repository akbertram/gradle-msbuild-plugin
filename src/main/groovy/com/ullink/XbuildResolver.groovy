package com.ullink

import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.gradle.api.GradleException
import org.gradle.util.VersionNumber

class XbuildResolver implements IExecutableResolver {
    XbuildResolver(){
        def execute = "mono --version".execute()
        execute.waitFor()
        if (execute.in.text == null)
            throw new GradleException("Mono must be on PATH.")
    }

    @Override
    ProcessBuilder executeDotNet(File exe) {
        return new ProcessBuilder("mono", exe.toString())
    }

    void setupExecutable(Msbuild msbuild) {
        msbuild.executable = 'xbuild.exe'
        msbuild.msbuildDir = getXBuildDir(msbuild)
    }

    public static String getXBuildDir(Msbuild msbuild) {
        if (msbuild.version != null)
            msbuild.logger.info("MSBuild version explicitly set to: '${msbuild.version}'")

        List<String> xbuildRoots = [getMonoBinaryRootDirectory()] + getOSXMonoRootDirectories()
        /*
            we can encounter the following scenario:
                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/4.5
                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/xbuild/14.0
                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/xbuild/12.0
                /Library/Frameworks/Mono.framework/Versions/3.12.0/lib/mono/4.5
                /Library/Frameworks/Mono.framework/Versions/3.12.0/lib/mono/xbuild/12.0

            so just make sure we're sorting (additionally) by the last path segment's version, which would yield:

                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/xbuild/14.0
                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/xbuild/12.0
                /Library/Frameworks/Mono.framework/Versions/3.12.0/lib/mono/xbuild/12.0
                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/4.5
                /Library/Frameworks/Mono.framework/Versions/3.12.0/lib/mono/4.5
         */
        def existingXBuilds = xbuildRoots
            .collectMany { ["$it/lib/mono", "$it/lib/mono/xbuild"] }
            .collectMany { getVersionDirectories(it) }
            .collect {
                String versionString = it.substring(it.lastIndexOf('/') + 1);
                [it, Float.parseFloat(versionString)]
            }
            .sort { -it[1] }
            .collectMany { [
                [new File(it[0], "xbuild.exe"), it[1]],
                [new File(it[0], "bin/xbuild.exe"), it[1]]
            ]}
            .findAll { it[0].exists() }

        def foundXBuild = existingXBuilds.find { msbuild.version == null || msbuild.version.equals(String.valueOf(it[1])) }
        if (foundXBuild != null) {
            File file = foundXBuild[0]
            msbuild.logger.info("Resolved xbuild to: ${file.absolutePath}")
            return file.getParent()
        }

        throw new GradleException("Cannot find an xbuild binary. Is mono SDK installed? " +
                "(Existing binaries: ${existingXBuilds.collect{it[0]}})")
    }

    private static List<String> getOSXMonoRootDirectories() {
        getVersionDirectories("/Library/Frameworks/Mono.framework/Versions/")
    }

    private static List<String> getVersionDirectories(String path) {
        File file = new File(path)
        if (!file.exists()) {
            return []
        }

        return file.listFiles((FileFilter) DirectoryFileFilter.INSTANCE)
                .collect { [VersionNumber.parse(it.name), it.absolutePath] }
                .findAll { !VersionNumber.UNKNOWN.equals(it[0]) }
                .sort { a, b -> b[0].compareTo(a[0]) }
                .collect { it[1] }
    }


    private static String getMonoBinaryRootDirectory() {
        def which = "which mono".execute()
        which.waitFor()
        def monoRoot = which.in.text
        if (monoRoot == null || monoRoot.isEmpty())
            throw new GradleException("Can't get mono location. Mono default installation prefix is usually /usr/lib/")
        monoRoot - "/bin/mono\n"
    }
}
