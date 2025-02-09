/*
 * Copyright (c) 2004-2021, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.dxf2.events.trackedentity.store;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hisp.dhis.dxf2.events.aggregates.AggregateContext;
import org.hisp.dhis.dxf2.events.event.DataValue;
import org.hisp.dhis.dxf2.events.event.Event;
import org.hisp.dhis.dxf2.events.event.Note;
import org.hisp.dhis.dxf2.events.trackedentity.store.mapper.EventDataValueRowCallbackHandler;
import org.hisp.dhis.dxf2.events.trackedentity.store.mapper.EventRowCallbackHandler;
import org.hisp.dhis.dxf2.events.trackedentity.store.mapper.NoteRowCallbackHandler;
import org.hisp.dhis.dxf2.events.trackedentity.store.query.EventQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

/**
 * @author Luciano Fiandesio
 */
@Repository
public class DefaultEventStore
    extends
    AbstractStore
    implements
    EventStore
{
    private final static String GET_EVENTS_SQL = EventQuery.getQuery();

    private final static String GET_DATAVALUES_SQL = "select psi.uid as key, " +
        "psi.eventdatavalues " +
        "from programstageinstance psi " +
        "where psi.programstageinstanceid in (:ids)";

    private final static String GET_NOTES_SQL = "select pi.uid as key, tec.uid, tec.commenttext, " +
        "tec.creator, tec.created " +
        "from trackedentitycomment tec " +
        "join programstageinstancecomments psic " +
        "on tec.trackedentitycommentid = psic.trackedentitycommentid " +
        "join programinstance pi on psic.programstageinstanceid = pi.programinstanceid " +
        "where psic.programstageinstanceid in (:ids)";

    private final static String ACL_FILTER_SQL = "CASE WHEN p.type = 'WITHOUT_REGISTRATION' THEN " +
        "psi.programstageid in (:programStageIds) and p.trackedentitytypeid in (:trackedEntityTypeIds) else true END " +
        "AND pi.programid IN (:programIds)";

    private final static String ACL_FILTER_SQL_NO_PROGRAM_STAGE = "CASE WHEN p.type = 'WITHOUT_REGISTRATION' THEN " +
        "p.trackedentitytypeid in (:trackedEntityTypeIds) else true END " +
        "AND pi.programid IN (:programIds)";

    public DefaultEventStore( JdbcTemplate jdbcTemplate )
    {
        super( jdbcTemplate );
    }

    @Override
    String getRelationshipEntityColumn()
    {
        return "programstageinstanceid";
    }

    @Override
    public Multimap<String, Event> getEventsByEnrollmentIds( List<Long> enrollmentsId, AggregateContext ctx )
    {
        List<List<Long>> enrollmentIdsPartitions = Lists.partition( enrollmentsId, PARITITION_SIZE );

        Multimap<String, Event> eventMultimap = ArrayListMultimap.create();

        enrollmentIdsPartitions
            .forEach( partition -> eventMultimap.putAll( getEventsByEnrollmentIdsPartitioned( partition, ctx ) ) );

        return eventMultimap;
    }

    private Multimap<String, Event> getEventsByEnrollmentIdsPartitioned( List<Long> enrollmentsId,
        AggregateContext ctx )
    {
        EventRowCallbackHandler handler = new EventRowCallbackHandler();

        List<Long> programStages = ctx.getProgramStages();

        if ( programStages.isEmpty() )
        {
            jdbcTemplate.query( withAclCheck( GET_EVENTS_SQL, ctx, ACL_FILTER_SQL_NO_PROGRAM_STAGE ),
                createIdsParam( enrollmentsId )
                    .addValue( "trackedEntityTypeIds", ctx.getTrackedEntityTypes() )
                    .addValue( "programStageIds", programStages )
                    .addValue( "programIds", ctx.getPrograms() ),
                handler );
        }
        else
        {
            jdbcTemplate.query( withAclCheck( GET_EVENTS_SQL, ctx, ACL_FILTER_SQL ),
                createIdsParam( enrollmentsId )
                    .addValue( "trackedEntityTypeIds", ctx.getTrackedEntityTypes() )
                    .addValue( "programStageIds", programStages )
                    .addValue( "programIds", ctx.getPrograms() ),
                handler );
        }

        return handler.getItems();
    }

    @Override
    public Map<String, List<DataValue>> getDataValues( List<Long> programStageInstanceId )
    {
        List<List<Long>> psiIdsPartitions = Lists.partition( programStageInstanceId, PARITITION_SIZE );

        Map<String, List<DataValue>> dataValueListMultimap = new HashMap<>();

        psiIdsPartitions.forEach( partition -> dataValueListMultimap.putAll( getDataValuesPartitioned( partition ) ) );

        return dataValueListMultimap;
    }

    private Map<String, List<DataValue>> getDataValuesPartitioned( List<Long> programStageInstanceId )
    {
        EventDataValueRowCallbackHandler handler = new EventDataValueRowCallbackHandler();

        jdbcTemplate.query( GET_DATAVALUES_SQL, createIdsParam( programStageInstanceId ), handler );

        return handler.getItems();
    }

    @Override
    public Multimap<String, Note> getNotes( List<Long> eventIds )
    {
        return fetch( GET_NOTES_SQL, new NoteRowCallbackHandler(), eventIds );
    }
}
