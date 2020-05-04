package org.hisp.dhis.dxf2.events.event.context;

/*
 * Copyright (c) 2004-2020, University of Oslo
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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.text.StrSubstitutor;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.cache2k.integration.CacheLoader;
import org.hisp.dhis.category.CategoryCombo;
import org.hisp.dhis.dxf2.events.event.Event;
import org.hisp.dhis.organisationunit.FeatureType;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.program.ProgramType;
import org.hisp.dhis.trackedentity.TrackedEntityType;
import org.hisp.dhis.user.User;
import org.hisp.dhis.user.UserAccess;
import org.hisp.dhis.user.UserGroup;
import org.hisp.dhis.user.UserGroupAccess;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.google.common.collect.ImmutableMap;

/**
 * @author Luciano Fiandesio
 */
@Component( "workContextProgramsSupplier" )
public class ProgramSupplier extends AbstractSupplier<Map<String, Program>>
{
    private final static String PROGRAM_CACHE_KEY = "000P";

    // @formatter:off
    private final static String USER_ACCESS_SQL = "select eua.${column_name}, eua.useraccessid, ua.useraccessid, ua.access, ua.userid, u.uid " +
        "from ${table_name} eua " +
        "join useraccess ua on eua.useraccessid = ua.useraccessid " +
        "join users u on ua.userid = ua.userid " +
        "order by eua.${column_name}";

    private final static String USER_GROUP_ACCESS_SQL = "select ega.${column_name}, ega.usergroupaccessid, u.access, u.usergroupid, ug.uid " +
        "from ${table_name} ega " +
        "join usergroupaccess u on ega.usergroupaccessid = u.usergroupaccessid " +
        "join usergroup ug on u.usergroupid = ug.usergroupid " +
        "order by ega.${column_name}";

    // Caches the entire Program hierarchy, including Program Stages and ACL data
    private final Cache<String, Map<String, Program>> programsCache = new Cache2kBuilder<String, Map<String, Program>>() {}
        .name( "eventImportProgramCache" + RandomStringUtils.randomAlphabetic(5) )
        .expireAfterWrite( 30, TimeUnit.MINUTES ) // expire/refresh after 30 minutes
        .build();

    // Caches the User Groups and the Users belonging to each group
    private final Cache<Long, Set<User>> userGroupCache = new Cache2kBuilder<Long, Set<User>>() {}
        .name( "eventImportUserGroupCache" + RandomStringUtils.randomAlphabetic(5) )
        .expireAfterWrite( 60, TimeUnit.MINUTES )
        .permitNullValues( true )
        .loader( new CacheLoader<Long, Set<User>>()
        {
            @Override
            public Set<User> load(Long userGroupId) {
                return loadUserGroups( userGroupId );
            }
        } ).build() ;
    
    // @formatter:on

    public ProgramSupplier( NamedParameterJdbcTemplate jdbcTemplate )
    {
        super( jdbcTemplate );
    }

    @Override
    public Map<String, Program> get( List<Event> eventList )
    {
        Map<String, Program> programMap = programsCache.get( PROGRAM_CACHE_KEY );
        if ( programMap == null )
        {
            programMap = loadPrograms();

            Map<Long, Set<OrganisationUnit>> ouMap = loadOrgUnits();
            Map<Long, Set<UserAccess>> programUserAccessMap = loadUserAccessesForPrograms();
            Map<Long, Set<UserAccess>> programStageUserAccessMap = loadUserAccessesForProgramStages();
            Map<Long, Set<UserAccess>> tetUserAccessMap = loadUserAccessesForTrackedEntityTypes();

            Map<Long, Set<UserGroupAccess>> programUserGroupAccessMap = loadGroupUserAccessesForPrograms();
            Map<Long, Set<UserGroupAccess>> programStageUserGroupAccessMap = loadGroupUserAccessesForProgramStages();
            Map<Long, Set<UserGroupAccess>> tetUserGroupAccessMap = loadGroupUserAccessesForTrackedEntityTypes();

            for ( Program program : programMap.values() )
            {
                program.setOrganisationUnits( ouMap.getOrDefault( program.getId() , new HashSet<>() ));
                program.setUserAccesses( programUserAccessMap.getOrDefault( program.getId(), new HashSet<>() ) );
                program.setUserGroupAccesses( programUserGroupAccessMap.getOrDefault( program.getId(), new HashSet<>() ) );
                TrackedEntityType trackedEntityType = program.getTrackedEntityType();
                if ( trackedEntityType != null )
                {
                    trackedEntityType.setUserAccesses( tetUserAccessMap.getOrDefault( trackedEntityType.getId(), new HashSet<>() ) );
                    trackedEntityType.setUserGroupAccesses( tetUserGroupAccessMap.getOrDefault( trackedEntityType.getId(), new HashSet<>() ) );
                }
                
                for ( ProgramStage programStage : program.getProgramStages() )
                {
                    programStage.setUserAccesses( programStageUserAccessMap.getOrDefault( programStage.getId(), new HashSet<>() ) );
                    programStage.setUserGroupAccesses( programStageUserGroupAccessMap.getOrDefault( programStage.getId(), new HashSet<>() ) );
                }
            }

            programsCache.put( PROGRAM_CACHE_KEY, programMap );
        }
        return programMap;
    }

    private Map<Long, Set<OrganisationUnit>> loadOrgUnits()
    {
        final String sql = "select p.programid, o.uid, o.organisationunitid from program_organisationunits p join organisationunit o on p.organisationunitid = o.organisationunitid order by programid";
        return jdbcTemplate.query( sql, ( ResultSet rs ) -> {
            Map<Long, Set<OrganisationUnit>> results = new HashMap<>();
            long programId = 0;
            while ( rs.next() )
            {
                if ( programId != rs.getLong( "programid" ) )
                {
                    Set<OrganisationUnit> ouSet = new HashSet<>();
                    ouSet.add( toOrganisationUnit( rs ) );
                    results.put( rs.getLong( "programid" ), ouSet );
                    programId = rs.getLong( "programid" );
                }
                else
                {
                    results.get( rs.getLong( "programid" ) ).add( toOrganisationUnit( rs ) );
                }
            }
            return results;
        } );
    }

    private Map<Long, Set<UserAccess>> loadUserAccessesForPrograms()
    {
        return fetchUserAccesses( replaceAclQuery( USER_ACCESS_SQL, "programuseraccesses", "programid" ), "programid" );
    }

    private Map<Long, Set<UserAccess>> loadUserAccessesForProgramStages()
    {
        return fetchUserAccesses( replaceAclQuery( USER_ACCESS_SQL, "programstageuseraccesses", "programstageid" ),
            "programstageid" );
    }

    private Map<Long, Set<UserAccess>> loadUserAccessesForTrackedEntityTypes()
    {
        return fetchUserAccesses(
            replaceAclQuery( USER_ACCESS_SQL, "trackedentitytypeuseraccesses", "trackedentitytypeid" ),
            "trackedentitytypeid" );
    }

    private Map<Long, Set<UserAccess>> fetchUserAccesses( String sql, String column )
    {
        return jdbcTemplate.query( sql, ( ResultSet rs ) -> {
            Map<Long, Set<UserAccess>> results = new HashMap<>();
            long programStageId = 0;
            while ( rs.next() )
            {
                if ( programStageId != rs.getLong( column ) )
                {
                    Set<UserAccess> aclSet = new HashSet<>();
                    aclSet.add( toUserAccess( rs ) );
                    results.put( rs.getLong( column ), aclSet );

                    programStageId = rs.getLong( column );
                }
                else
                {
                    results.get( rs.getLong( column ) ).add( toUserAccess( rs ) );
                }
            }
            return results;
        } );
    }
    
    private Map<Long, Set<UserGroupAccess>> loadGroupUserAccessesForPrograms()
    {
        return fetchUserGroupAccess( replaceAclQuery( USER_GROUP_ACCESS_SQL, "programusergroupaccesses", "programid" ),
            "programid" );
    }

    private Map<Long, Set<UserGroupAccess>> loadGroupUserAccessesForProgramStages()
    {
        // TODO: can't use replace because the table programstageusergroupaccesses should use 'programstageid' as column name
        final String sql = "select psuga.programid as programstageid, psuga.usergroupaccessid, u.access, u.usergroupid, ug.uid "
            + "from programstageusergroupaccesses psuga "
            + "join usergroupaccess u on psuga.usergroupaccessid = u.usergroupaccessid "
            + "join usergroup ug on u.usergroupid = ug.usergroupid " + "order by programstageid";

        return fetchUserGroupAccess( sql, "programstageid" );
    }

    private Map<Long, Set<UserGroupAccess>> loadGroupUserAccessesForTrackedEntityTypes()
    {
        return fetchUserGroupAccess(
            replaceAclQuery( USER_GROUP_ACCESS_SQL, "trackedentitytypeusergroupaccesses", "trackedentitytypeid" ),
            "trackedentitytypeid" );
    }
    
    private Map<Long, Set<UserGroupAccess>> fetchUserGroupAccess( String sql, String column )
    {
        return jdbcTemplate.query( sql, ( ResultSet rs ) -> {
            Map<Long, Set<UserGroupAccess>> results = new HashMap<>();
            long entityId = 0;
            while ( rs.next() )
            {
                if ( entityId != rs.getLong( column ) )
                {
                    Set<UserGroupAccess> aclSet = new HashSet<>();
                    aclSet.add( toUserGroupAccess( rs ) );
                    results.put( rs.getLong( column ), aclSet );

                    entityId = rs.getLong( column );
                }
                else
                {
                    results.get( rs.getLong( column ) ).add( toUserGroupAccess( rs ) );
                }
            }
            return results;
        } );
    }

    private Map<String, Program> loadPrograms()
    {
        final String sql = "select p.programid, p.publicaccess, p.uid, p.name, p.type, tet.trackedentitytypeid, tet.publicaccess as tet_public_access, tet.uid as tet_uid,  c.categorycomboid as catcombo_id, c.uid as catcombo_uid, c.name as catcombo_name, " +
                "            ps.programstageid as ps_id, ps.uid as ps_uid, ps.featuretype as ps_feature_type, ps.sort_order, ps.publicaccess as ps_public_access " +
                "            from program p LEFT JOIN categorycombo c on p.categorycomboid = c.categorycomboid " +
                "                    LEFT JOIN trackedentitytype tet on p.trackedentitytypeid = tet.trackedentitytypeid " +
                "                    LEFT JOIN programstage ps on p.programid = ps.programid " +
                "                    LEFT JOIN program_organisationunits pou on p.programid = pou.programid " +
                "                    LEFT JOIN organisationunit ou on pou.organisationunitid = ou.organisationunitid " +
                "            group by p.programid, p.uid, p.name, p.type, tet.trackedentitytypeid, c.categorycomboid, c.uid, c.name, ps.programstageid, ps.uid , ps.featuretype, ps.sort_order " +
                "            order by p.programid, ps.sort_order";

        return jdbcTemplate.query( sql, ( ResultSet rs ) -> {
            Map<String, Program> results = new HashMap<>();
            long programId = 0;
            while ( rs.next() )
            {
                if ( programId != rs.getLong( "programid" ) )
                {
                    Set<ProgramStage> programStages = new HashSet<>();
                    Program program = new Program();
                    program.setId( rs.getLong( "programid" ) );
                    program.setUid( rs.getString( "uid" ) );
                    program.setName( rs.getString( "name" ) );
                    program.setProgramType( ProgramType.fromValue( rs.getString( "type" ) ) );
                    program.setPublicAccess( rs.getString( "publicaccess" ) );

                    programStages.add( toProgramStage( rs ) );

                    CategoryCombo categoryCombo = new CategoryCombo();
                    categoryCombo.setId( rs.getLong( "catcombo_id" ) );
                    categoryCombo.setUid( rs.getString( "catcombo_uid" ) );
                    categoryCombo.setName( rs.getString( "catcombo_name" ) );
                    program.setCategoryCombo( categoryCombo );

                    long tetId = rs.getLong( "trackedentitytypeid" );
                    if ( tetId != 0)
                    {
                        TrackedEntityType trackedEntityType = new TrackedEntityType();
                        trackedEntityType.setId( tetId );
                        trackedEntityType.setUid( rs.getString( "tet_uid" ) );
                        trackedEntityType.setPublicAccess( rs.getString( "tet_public_access" ) );
                        program.setTrackedEntityType( trackedEntityType );
                    }
                    
                    program.setProgramStages( programStages );
                    results.put( rs.getString( "uid" ), program );

                    programId = program.getId();
                }
                else
                {
                    results.get( rs.getString( "uid" ) ).getProgramStages().add( toProgramStage( rs ) );
                }
            }
            return results;
        } );
    }

    private ProgramStage toProgramStage( ResultSet rs )
        throws SQLException
    {
        ProgramStage programStage = new ProgramStage();
        programStage.setId( rs.getLong( "ps_id" ) );
        programStage.setUid( rs.getString( "ps_uid" ) );
        programStage.setSortOrder( rs.getInt( "sort_order" ) );
        programStage.setPublicAccess( rs.getString( "ps_public_access" ) );
        programStage.setFeatureType(
            rs.getString( "ps_feature_type" ) != null ? FeatureType.getTypeFromName( rs.getString( "ps_feature_type" ) )
                : FeatureType.NONE );

        return programStage;
    }

    private OrganisationUnit toOrganisationUnit( ResultSet rs )
        throws SQLException
    {
        OrganisationUnit ou = new OrganisationUnit();
        ou.setUid( rs.getString( "uid" ) );
        return ou;
    }

    private UserAccess toUserAccess( ResultSet rs )
        throws SQLException
    {
        UserAccess userAccess = new UserAccess();
        userAccess.setId( rs.getInt( "useraccessid" ) );
        userAccess.setAccess( rs.getString( "access" ) );
        User user = new User();
        user.setId( rs.getLong( "userid" ) );
        user.setUid( rs.getString( "uid" ) );
        userAccess.setUser( user );
        return userAccess;
    }

    private UserGroupAccess toUserGroupAccess( ResultSet rs )
        throws SQLException
    {
        UserGroupAccess userGroupAccess = new UserGroupAccess();
        userGroupAccess.setId( rs.getInt( "usergroupaccessid" ) );
        userGroupAccess.setAccess( rs.getString( "access" ) );
        UserGroup userGroup = new UserGroup();
        userGroup.setId( rs.getLong( "usergroupid" ) );
        userGroupAccess.setUserGroup( userGroup );
        userGroup.setUid( rs.getString( "uid" ) );
        // TODO This is not very efficient for large DHIS2 installations:
        //  it would be better to run a direct query in the Access Layer
        // to determine if the user belongs to the group
        userGroup.setMembers( userGroupCache.get( userGroup.getId() ) );
        return userGroupAccess;
    }

    private Set<User> loadUserGroups( Long userGroupId)
    {
        final String sql = "select ug.uid, ug.usergroupid, u.uid user_uid, u.userid user_id from usergroupmembers ugm "
            + "join usergroup ug on ugm.usergroupid = ug.usergroupid join users u on ugm.userid = u.userid where ug.usergroupid = "
            + userGroupId;

        return jdbcTemplate.query( sql, ( ResultSet rs ) -> {

            Set<User> users = new HashSet<>();
            while ( rs.next() )
            {

                User user = new User();
                user.setUid( rs.getString( "user_uid" ) );
                user.setId( rs.getLong( "user_id" ) );

                users.add( user );
            }

            return users;
        } );
    }

    private String replaceAclQuery(String sql, String tableName, String column) {
        // @formatter:off
        StrSubstitutor sub = new StrSubstitutor( ImmutableMap.<String, String>builder()
                .put( "table_name", tableName)
                .put( "column_name", column )
                .build() );
        // @formatter:on
        return sub.replace( sql );
    }
}
