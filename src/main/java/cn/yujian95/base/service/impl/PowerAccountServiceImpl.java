package cn.yujian95.base.service.impl;

import cn.yujian95.base.common.security.JwtTokenUtil;
import cn.yujian95.base.dto.param.PowerAccountRegisterParam;
import cn.yujian95.base.dto.param.PowerAccountStatusParam;
import cn.yujian95.base.entity.*;
import cn.yujian95.base.mapper.PowerAccountMapper;
import cn.yujian95.base.mapper.PowerAccountPermissionRelationMapper;
import cn.yujian95.base.mapper.PowerAccountRoleRelationMapper;
import cn.yujian95.base.mapper.dao.PowerAccountPermissionRelationDao;
import cn.yujian95.base.mapper.dao.PowerAccountRoleRelationDao;
import cn.yujian95.base.service.ILogAccountLoginService;
import cn.yujian95.base.service.IPowerAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author YuJian95  clj9509@163.com
 * @date 2020/1/20
 */

@Service
public class PowerAccountServiceImpl implements IPowerAccountService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PowerAccountServiceImpl.class);

    @Resource
    private JwtTokenUtil jwtTokenUtil;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Value("${jwt.tokenHead}")
    private String tokenHead;

    @Resource
    private UserDetailsService userDetailsService;

    @Resource
    private PowerAccountMapper accountMapper;

    @Resource
    private PowerAccountRoleRelationDao accountRoleRelationDao;

    @Resource
    private PowerAccountPermissionRelationDao accountPermissionRelationDao;

    @Resource
    private PowerAccountPermissionRelationMapper accountPermissionRelationMapper;

    @Resource
    private PowerAccountRoleRelationMapper accountRoleRelationMapper;

    @Resource
    private ILogAccountLoginService logAccountLoginService;

    /**
     * 获取帐号信息
     *
     * @param name 帐号名
     * @return 帐号信息
     */
    @Override
    public Optional<PowerAccount> getByName(String name) {

        PowerAccountExample example = new PowerAccountExample();

        example.createCriteria()
                .andNameEqualTo(name);

        return Optional.ofNullable(accountMapper.selectByExample(example).get(0));
    }

    /**
     * 刷新token
     *
     * @param oldToken 原来的token
     * @return 新token
     */
    @Override
    public String refreshToken(String oldToken) {
        String token = oldToken.substring(tokenHead.length());

        if (jwtTokenUtil.canRefresh(token)) {
            return jwtTokenUtil.refreshToken(token);
        }

        return null;
    }

    /**
     * 判断用户名是否已使用
     *
     * @param name 用户名
     * @return 是否存在
     */
    @Override
    public boolean count(String name) {
        PowerAccountExample example = new PowerAccountExample();

        example.createCriteria()
                .andNameEqualTo(name);

        return accountMapper.countByExample(example) > 0;
    }

    /**
     * 判断用户是否存在
     *
     * @param id 用户编号
     * @return 是否存在
     */
    @Override
    public boolean count(Long id) {
        PowerAccountExample example = new PowerAccountExample();

        example.createCriteria()
                .andIdEqualTo(id);

        return accountMapper.countByExample(example) > 0;
    }


    /**
     * 账号注册功能
     *
     * @param param 账号注册参数（账号，密码）
     * @return 是否成功
     */
    @Override
    public boolean register(PowerAccountRegisterParam param) {
        PowerAccount account = new PowerAccount();

        account.setName(param.getName());
        account.setPassword(passwordEncoder.encode(param.getPassword()));
        account.setStatus(1);

        account.setGmtCreate(new Date());
        account.setGmtModified(new Date());

        return accountMapper.insertSelective(account) > 0;
    }

    /**
     * 登录后返回 jwt 字符串
     *
     * @param name     帐号名
     * @param password 密码
     * @return 生成的JWT的token
     */
    @Override
    public Optional<String> login(String name, String password) {

        String jwt = null;

        // 客户端加密后传递账号密码
        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(name);

            // 密码不正确
            if (!passwordEncoder.matches(password, userDetails.getPassword())) {

                LOGGER.info("user :{} login fail , wrong password .", name);
                throw new BadCredentialsException("密码不正确");
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(userDetails,
                    null, userDetails.getAuthorities());

            SecurityContextHolder.getContext().setAuthentication(authentication);

            String token = jwtTokenUtil.generateToken(userDetails);

            // 这里需要记录登录时间
            updateLoginTime(name);
            logAccountLoginService.insert(name);

            jwt = tokenHead + "" + token;

        } catch (AuthenticationException e) {
            LOGGER.warn("user :{} , login fail :{}", name, e.getMessage());
        }


        return Optional.ofNullable(jwt);
    }

    /**
     * 更新账号状态
     *
     * @param param 账号编号、账号状态（1：开启，0：关闭）
     * @return 是否成功
     */
    @Override
    public boolean updateStatus(PowerAccountStatusParam param) {
        PowerAccount account = new PowerAccount();

        account.setId(param.getAccountId());
        account.setStatus(param.getStatus());

        account.setPassword(null);
        account.setGmtModified(new Date());

        return accountMapper.updateByPrimaryKeySelective(account) > 0;
    }

    /**
     * 更新密码
     *
     * @param accountId 帐号编号
     * @param password  密码
     * @return 更新结果
     */
    @Override
    public boolean
    updatePassword(Long accountId, String password) {
        PowerAccount account = new PowerAccount();

        account.setId(accountId);
        account.setPassword(passwordEncoder.encode(password));
        account.setGmtModified(new Date());

        return accountMapper.updateByPrimaryKeySelective(account) > 0;

    }

    /**
     * 修改用户角色关系
     *
     * @param accountId 帐号id
     * @param roleIdList   角色id列表
     * @return 成功记录
     */
    @Override
    public int updateRole(Long accountId, List<Long> roleIdList) {
        int count = roleIdList == null ? 0 : roleIdList.size();

        //先删除原来的关系
        PowerAccountRoleRelationExample accountRoleRelationExample = new PowerAccountRoleRelationExample();

        accountRoleRelationExample.createCriteria()
                .andAccountIdEqualTo(accountId);

        accountRoleRelationMapper.deleteByExample(accountRoleRelationExample);

        //建立新关系
        if (!CollectionUtils.isEmpty(roleIdList)) {

            List<PowerAccountRoleRelation> list = new ArrayList<>();

            for (Long roleId : roleIdList) {
                PowerAccountRoleRelation roleRelation = new PowerAccountRoleRelation();
                roleRelation.setAccountId(accountId);
                roleRelation.setRoleId(roleId);
                list.add(roleRelation);
            }

            accountRoleRelationDao.insertList(list);
        }

        return count;
    }

    /**
     * 更新最后登录时间
     *
     * @param name 用户名称
     */
    @Override
    public void updateLoginTime(String name) {
        Optional<PowerAccount> accountOptional = getByName(name);

        // 账号存在
        if (accountOptional.isPresent()) {
            PowerAccount account = accountOptional.get();

            account.setPassword(null);
            account.setLoginTime(new Date());

            accountMapper.updateByPrimaryKeySelective(account);
        }
    }

    /**
     * 修改用户的+-权限
     *
     * @param accountId        用户id
     * @param permissionIdList 权限列表
     * @return 成功记录
     */
    @Override
    public int updatePermission(Long accountId, List<Long> permissionIdList) {
        // 删除原所有权限关系
        PowerAccountPermissionRelationExample relationExample = new PowerAccountPermissionRelationExample();
        relationExample.createCriteria().andAccountIdEqualTo(accountId);

        accountPermissionRelationMapper.deleteByExample(relationExample);

        // 获取用户所有角色权限
        List<PowerPermission> permissionList = accountRoleRelationDao.getRolePermissionList(accountId);

        List<Long> rolePermissionList = permissionList.stream()
                .map(PowerPermission::getId)
                .collect(Collectors.toList());

        if (!CollectionUtils.isEmpty(permissionIdList)) {

            List<PowerAccountPermissionRelation> relationList = new ArrayList<>();

            // 筛选出 +权限
            List<Long> addPermissionIdList = permissionIdList.stream()
                    // 筛选出+权限
                    .filter(permissionId -> !rolePermissionList.contains(permissionId))
                    .collect(Collectors.toList());

            // 筛选出-权限
            List<Long> subPermissionIdList = rolePermissionList.stream()
                    // 筛选出-权限
                    .filter(permissionId -> !permissionIdList.contains(permissionId))
                    .collect(Collectors.toList());

            // 插入 + - 权限关系
            relationList.addAll(convert(accountId, 1, addPermissionIdList));
            relationList.addAll(convert(accountId, -1, subPermissionIdList));

            return accountPermissionRelationDao.insertList(relationList);
        }

        return 0;
    }

    /**
     * 获取帐号所有权限（包括角色权限 和 +-权限）
     *
     * @param accountId 账号编号
     * @return 权限列表
     */
    @Override
    public List<PowerPermission> listPermission(Long accountId) {
        return accountRoleRelationDao.getPermissionList(accountId);
    }

    /**
     * 将+-权限关系转化为对象
     */
    private List<PowerAccountPermissionRelation> convert(Long accountId, Integer type, List<Long> permissionIdList) {
        return permissionIdList.stream()
                .map(permissionId -> {

                    PowerAccountPermissionRelation relation = new PowerAccountPermissionRelation();

                    relation.setAccountId(accountId);
                    relation.setType(type);
                    relation.setPermissionId(permissionId);
                    return relation;

                }).collect(Collectors.toList());
    }
}
