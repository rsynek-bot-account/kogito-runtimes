/*
 * Copyright 2005 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.modelcompiler;

import org.kie.api.runtime.KieSession;
import org.kie.kogito.rules.RuleUnit;
import org.kie.kogito.rules.impl.AbstractRuleUnitInstance;

public class SessionRuleUnitInstance extends AbstractRuleUnitInstance<SessionMemory> {

    public SessionRuleUnitInstance( RuleUnit<SessionMemory> unit, SessionMemory memory, KieSession kieSession ) {
        super( unit, memory, kieSession );
    }

    protected void bind( KieSession kieSession, SessionMemory memory ) {
        memory.getDataSource().drainInto( kieSession::insert );
    }
}