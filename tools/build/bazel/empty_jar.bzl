# Copyright 2015 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

def _impl(ctx):
    dir = ctx.label.name
    jar = ctx.outputs.jar

    cmd = [
        "mkdir readme && touch readme/README && jar cf %s readme/README" % (jar.path),
    ]

    ctx.action(
        outputs = [jar],
        progress_message = "Generating empty jar for %s" % ctx.attr.name,
        command = ";\n".join(cmd),
    )

empty_jar = rule(
    implementation = _impl,
    outputs = {"jar": "%{name}.jar"},
)
