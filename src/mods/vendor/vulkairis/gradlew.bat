@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Vulkairis: Fabric's dev launcher cannot handle a Gradle home whose path
@rem  contains non-ASCII characters (e.g. a user profile named "Molina Nunez"
@rem  with the enye). It mangles those paths, so Fabric Loader/Mixin classes
@rem  end up in the wrong classloader and the client fails at startup with
@rem  "cannot be cast to IMixinConfigPlugin" / "trying to load FabricLoaderImpl
@rem  from target class loader".
@rem
@rem  When GRADLE_USER_HOME is not already set, route it through a short ASCII
@rem  junction to the real Gradle home. This is a no-op when the profile is
@rem  already ASCII or when the junction cannot be created.
@rem
@rem ##########################################################################
if not defined GRADLE_USER_HOME (
  set "VULKAIRIS_GRADLE_HOME=%~d0\gradle-ascii"
  if not exist "%VULKAIRIS_GRADLE_HOME%\caches" (
    if exist "%USERPROFILE%\.gradle" cmd /c mklink /J "%VULKAIRIS_GRADLE_HOME%" "%USERPROFILE%\.gradle" >nul 2>&1
  )
  if exist "%VULKAIRIS_GRADLE_HOME%\caches" set "GRADLE_USER_HOME=%VULKAIRIS_GRADLE_HOME%"
)

@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables, and ensure extensions are enabled
setlocal EnableExtensions

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m" "-Dgradle.user.home=C:\gradle-ascii"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:execute
@rem Setup the command line



@rem Execute Gradle
@rem endlocal doesn't take effect until after the line is parsed and variables are expanded
@rem which allows us to clear the local environment before executing the java command
endlocal & set "GRADLE_USER_HOME=C:\gradle-ascii" & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" "--gradle-user-home" "C:\gradle-ascii" %* & call :exitWithErrorLevel

:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%
