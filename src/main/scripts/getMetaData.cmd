@REM   Licensed to the Apache Software Foundation (ASF) under one
@REM   or more contributor license agreements.  See the NOTICE file
@REM   distributed with this work for additional information
@REM   regarding copyright ownership.  The ASF licenses this file
@REM   to you under the Apache License, Version 2.0 (the
@REM   "License"); you may not use this file except in compliance
@REM   with the License.  You may obtain a copy of the License at
@REM
@REM    http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM   Unless required by applicable law or agreed to in writing,
@REM   software distributed under the License is distributed on an
@REM   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM   KIND, either express or implied.  See the License for the
@REM   specific language governing permissions and limitations
@REM   under the License.
@echo off

@if not defined UIMA_HOME goto USAGE_UIMA
@goto RUN

:USAGE_UIMA
@echo UIMA_HOME environment variable is not set 
@goto end

:RUN

@if .%1 == . goto usage
@if .%2 == . goto usage

@rem call common script that will launch the Uima AS service
@call "%UIMA_HOME%\bin\runUimaClass.bat" org.apache.uima.examples.as.GetMetaRequest %*

@goto end
:usage
 @echo not enough arguments
 @echo Usage: getMetaData brokerUri serviceName [-verbose]
:end
